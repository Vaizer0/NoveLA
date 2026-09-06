package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsAudioFormat
import my.noveldokusha.text_to_speech.TtsAudioExporter
import my.noveldokusha.text_to_speech.TtsExportException
import my.noveldokusha.text_to_speech.TtsTextPreparer
import my.noveldokusha.text_to_speech.timelineToJson
import org.json.JSONArray
import timber.log.Timber
import java.io.File

/** Cancellable AUDIO stage. Produces durable WAV + timeline JSON and then queues VIDEO. */
class TtsAudioExportWorker(private val context: Context, workerParameters: WorkerParameters) : CoroutineWorker(context, workerParameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
        fun appDatabase(): AppDatabase
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val request = readRequest() ?: return Result.failure()
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java)
        val prefs = entry.appPreferences()
        val database = entry.appDatabase()
        if (request.format != TtsAudioFormat.WAV) return Result.failure()

        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val tempWav = File(tempDir, "$jobId.wav")
        var audioUri: Uri? = null
        var timelineUri: Uri? = null
        try {
            if (!isDirectoryAccessible(request.outputDirectoryUri)) throw TtsExportException(context.getString(StringsR.string.tts_audio_export_dir_error))
            val novelFolderUri = resolveNovelFolder(request) ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_folder_error))
            TtsAudioQueue.updateState(prefs, jobId) { it?.copy(status = TtsAudioJobStatus.RUNNING, phase = "AUDIO", progress = 0) }

            val chapterText = fetchChapterText(database, prefs, request)
                ?: throw TtsExportException(context.getString(if (request.source == TtsAudioSource.TRANSLATED) StringsR.string.tts_audio_export_no_translation else StringsR.string.tts_audio_export_no_download))
            val paragraphs = TtsTextPreparer.paragraphsFromBody(chapterText, prefs.effectiveRegexRules(request.novelUrl))
            val suffix = when (request.source) {
                TtsAudioSource.ORIGINAL -> context.getString(StringsR.string.tts_audio_file_suffix_original)
                TtsAudioSource.TRANSLATED -> context.getString(StringsR.string.tts_audio_file_suffix_translated)
                TtsAudioSource.ASK_EVERY_TIME -> ""
            }
            val base = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)}"
            val fileName = "$base${if (suffix.isBlank()) "" else " $suffix"}.wav"
            val timelineName = "$base${if (suffix.isBlank()) "" else " $suffix"}.timeline.json"
            val result = TtsAudioExporter(context).exportAudio(request, paragraphs, tempWav, fileName) { fraction ->
                val percent = (fraction * 100f).toInt().coerceIn(0, 100)
                TtsAudioQueue.updateState(prefs, jobId) { it?.copy(progress = percent, phase = "AUDIO") }
            }
            require(tempWav.length() > 44L) { "Generated WAV is empty" }
            audioUri = withContext(Dispatchers.IO) { DocumentsContract.createDocument(context.contentResolver, novelFolderUri, MIME_WAV, fileName) }
                ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
            copyFileToUri(tempWav, audioUri!!)
            timelineUri = withContext(Dispatchers.IO) { DocumentsContract.createDocument(context.contentResolver, novelFolderUri, MIME_JSON, timelineName) }
                ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
            writeTextToUri(timelineToJson(result.timeline), timelineUri!!)

            val state = TtsAudioJobState(
                chapterUrl = request.chapterUrl, novelUrl = request.novelUrl, chapterTitle = request.chapterTitle,
                source = request.source, status = TtsAudioJobStatus.SUCCESS, message = "",
                documentUri = audioUri.toString(), displayName = fileName, progress = 100, phase = "AUDIO",
                audioUri = audioUri.toString(), timelineUri = timelineUri.toString(), outputDirectoryUri = request.outputDirectoryUri,
                videoSizeBytes = 0L, workRequestId = "",
            )
            TtsAudioQueue.updateState(prefs, jobId) { state }
            TtsVideoExportQueue.enqueue(context, prefs, jobId, state)
            tempWav.delete()
            return Result.success()
        } catch (e: CancellationException) {
            cleanupUri(audioUri); cleanupUri(timelineUri); tempWav.delete()
            TtsAudioQueue.updateState(prefs, jobId) { it?.copy(status = TtsAudioJobStatus.CANCELLED, phase = "AUDIO", progress = 0, documentUri = "", audioUri = "", timelineUri = "", workRequestId = "") }
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsAudio: AUDIO generation failed for $jobId")
            cleanupUri(audioUri); cleanupUri(timelineUri); tempWav.delete()
            val message = e.message ?: ""
            TtsAudioQueue.updateState(prefs, jobId) { it?.copy(status = TtsAudioJobStatus.FAILED, phase = "AUDIO", progress = 0, message = message, documentUri = "", audioUri = "", timelineUri = "") }
            return Result.failure()
        }
    }

    private suspend fun fetchChapterText(db: AppDatabase, prefs: AppPreferences, request: TtsAudioExportRequest): String? {
        return if (request.source == TtsAudioSource.TRANSLATED) {
            val source = request.translationSourceLang.ifBlank { prefs.translationPairForBook(request.novelUrl).source }
            val target = request.translationTargetLang.ifBlank { prefs.translationPairForBook(request.novelUrl).target }
            if (source.isBlank() || target.isBlank()) null
            else {
                val translation = db.chapterTranslationDao().getTranslations(request.chapterUrl, source, target) ?: return null
                if (translation.translatedParagraphs.isBlank()) null else runCatching {
                    val array = JSONArray(translation.translatedParagraphs)
                    if (array.length() == 0) null else (0 until array.length()).joinToString("\n\n") { array.getString(it) }
                }.getOrNull()
            }
        } else {
            db.chapterBodyDao().get(request.chapterUrl)?.body?.takeIf { it.isNotBlank() }
        }
    }

    private fun readRequest(): TtsAudioExportRequest? {
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE) ?: return null
        val novelUrl = inputData.getString(KEY_NOVEL_URL) ?: return null
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return null
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: return null
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, 0)
        val source = runCatching { TtsAudioSource.valueOf(inputData.getString(KEY_SOURCE) ?: return null) }.getOrNull() ?: return null
        if (source == TtsAudioSource.ASK_EVERY_TIME) return null
        return TtsAudioExportRequest(inputData.getString(KEY_JOB_ID) ?: return null, novelTitle, novelUrl, chapterUrl, chapterTitle, chapterIndex, source,
            inputData.getString(KEY_ENGINE_PACKAGE) ?: "", inputData.getString(KEY_VOICE_ID) ?: "", inputData.getFloat(KEY_SPEED, 1f), inputData.getFloat(KEY_PITCH, 1f),
            inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null, inputData.getString(KEY_FORMAT)?.let { TtsAudioFormat.valueOf(it) } ?: TtsAudioFormat.WAV,
            inputData.getString(KEY_TRANSLATION_SOURCE_LANG) ?: "", inputData.getString(KEY_TRANSLATION_TARGET_LANG) ?: "")
    }

    private suspend fun resolveNovelFolder(request: TtsAudioExportRequest): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val tree = Uri.parse(request.outputDirectoryUri)
            val root = DocumentsContract.getTreeDocumentId(tree)
            val wrapper = findOrCreateDirectory(tree, root, "NoveLA Audio") ?: return@runCatching null
            val novel = findOrCreateDirectory(tree, wrapper, sanitize(request.novelTitle, "novel")) ?: return@runCatching null
            DocumentsContract.buildDocumentUriUsingTree(tree, novel)
        }.getOrNull()
    }

    private fun findOrCreateDirectory(tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        context.contentResolver.query(children, null, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val display = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) if (cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR && cursor.getString(display).equals(name, true)) return cursor.getString(id)
        }
        return runCatching {
            DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), DocumentsContract.Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull()
    }

    private fun isDirectoryAccessible(uri: String): Boolean = runCatching {
        val tree = Uri.parse(uri)
        context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false
    }.getOrDefault(false)
    private suspend fun copyFileToUri(file: File, uri: Uri) = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } } ?: throw TtsExportException("Cannot write $uri") }
    private suspend fun writeTextToUri(text: String, uri: Uri) = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.writer(Charsets.UTF_8).use { w -> w.write(text) } } ?: throw TtsExportException("Cannot write $uri") }
    private fun cleanupUri(uri: Uri?) { if (uri != null) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
    private fun sanitize(name: String, fallback: String = "chapter") = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }
    companion object {
        const val KEY_JOB_ID = "jobId"; const val KEY_NOVEL_TITLE = "novelTitle"; const val KEY_NOVEL_URL = "novelUrl"; const val KEY_CHAPTER_URL = "chapterUrl"; const val KEY_CHAPTER_TITLE = "chapterTitle"; const val KEY_CHAPTER_INDEX = "chapterIndex"; const val KEY_SOURCE = "source"; const val KEY_ENGINE_PACKAGE = "enginePackage"; const val KEY_VOICE_ID = "voiceId"; const val KEY_SPEED = "speed"; const val KEY_PITCH = "pitch"; const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"; const val KEY_FORMAT = "format"; const val KEY_TRANSLATION_SOURCE_LANG = "translationSourceLang"; const val KEY_TRANSLATION_TARGET_LANG = "translationTargetLang"
        private const val MIME_WAV = "audio/wav"; private const val MIME_JSON = "application/json"
    }
}