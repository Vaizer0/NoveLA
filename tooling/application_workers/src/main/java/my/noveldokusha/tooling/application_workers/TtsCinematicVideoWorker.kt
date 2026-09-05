package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.cinematic_video.CinematicFfmpegAssetManager
import my.noveldokusha.cinematic_video.CinematicVideoException
import my.noveldokusha.cinematic_video.CinematicVideoRenderRequest
import my.noveldokusha.cinematic_video.CinematicVideoRenderer
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsAudioFormat
import my.noveldokusha.text_to_speech.TtsTextPreparer
import timber.log.Timber
import java.io.File
import org.json.JSONArray

/** Renders an existing downloaded audio file, generating a sidecar timeline only when needed. */
class TtsCinematicVideoWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CinematicVideoEntryPoint {
        fun appPreferences(): AppPreferences
        fun appDatabase(): AppDatabase
        fun notificationsCenter(): NotificationsCenter
    }

    override suspend fun doWork(): Result {
        val app = EntryPointAccessors.fromApplication(
            context.applicationContext,
            CinematicVideoEntryPoint::class.java,
        )
        val prefs = app.appPreferences()
        val database = app.appDatabase()
        val notifications = app.notificationsCenter()
        val request = readRequest() ?: return Result.failure()
        val notification = TtsAudioExportNotification(
            chapterTitle = request.chapterTitle,
            workRequestId = id.toString(),
            context = context,
            notificationsCenter = notifications,
        )

        val jobId = request.jobId
        var outputUri: Uri? = null
        val workDir = File(context.cacheDir, "tts_cinematic/$jobId").apply { mkdirs() }

        try {
            setForegroundSafely(notification)
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.RUNNING,
                    progress = 5,
                    message = "Preparing cinematic video…",
                )
            }
            notification.updateProgress(5)

            val novelFolder = resolveNovelFolder(request.outputDirectoryUri, request.novelTitle)
                ?: throw CinematicVideoException("NoveLA output folder could not be resolved")

            val sourceSuffix = when (request.source) {
                TtsAudioSource.ORIGINAL -> context.getString(StringsR.string.tts_audio_file_suffix_original)
                TtsAudioSource.TRANSLATED -> context.getString(StringsR.string.tts_audio_file_suffix_translated)
                TtsAudioSource.ASK_EVERY_TIME -> ""
            }
            val baseName = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)}"
            val prefix = if (sourceSuffix.isBlank()) baseName else "$baseName $sourceSuffix"
            val expectedTimelineName = "$prefix.timeline.json"
            val videoName = "$prefix.mp4"

            val audio = findExistingAudio(novelFolder, prefix)
                ?: throw CinematicVideoException("Downloaded audio was not found for this chapter. Generate the audio once, then create the video.")
            val actualAudioName = queryDisplayName(audio) ?: "$prefix.${request.preferredAudioExtension}"
            val timeline = findChild(novelFolder, expectedTimelineName)

            val stagedAudio = File(workDir, actualAudioName)
            copyUriToFile(audio, stagedAudio)

            val stagedTimeline = File(workDir, expectedTimelineName)
            if (timeline != null) {
                copyUriToFile(timeline, stagedTimeline)
            } else {
                if (request.source == TtsAudioSource.TRANSLATED && !hasTranslation(database, prefs, request)) {
                    throw CinematicVideoException(
                        "This translated audio predates cinematic timing data. Re-export the translated audio once to create its timing map. No TTS synthesis is performed by the video action."
                    )
                }
                val chapterText = fetchChapterText(database, prefs, request)
                    ?: throw CinematicVideoException("Chapter text is unavailable, so a timing map cannot be generated for the downloaded audio.")
                val regexRules = prefs.effectiveRegexRules(request.novelUrl)
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(progress = 12, message = "Using downloaded audio; preparing timing map…")
                }
                notification.updateProgress(12)
                CinematicExistingAudioTimeline.writeApproximateTimeline(
                    context = context,
                    request = request.toExportRequest(),
                    audioUri = audio,
                    audioFileName = actualAudioName,
                    chapterText = chapterText,
                    regexRules = regexRules,
                    outputFile = stagedTimeline,
                )
            }

            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(progress = 18, message = "Checking video renderer…")
            }
            notification.updateProgress(18)

            val ffmpegManager = CinematicFfmpegAssetManager(context)
            val ffmpegDir = ffmpegManager.prepare(workDir)
            ffmpegManager.verify(ffmpegDir)

            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(progress = 22, message = "Rendering cinematic video…")
            }
            notification.updateProgress(22)

            val stagedOutput = File(workDir, videoName)
            CinematicVideoRenderer().render(
                request = CinematicVideoRenderRequest(
                    audioFile = stagedAudio,
                    timelineFile = stagedTimeline,
                    outputFile = stagedOutput,
                    workingDirectory = workDir,
                    ffmpegDirectory = ffmpegDir,
                ),
            ) { fraction ->
                val percent = (22 + (fraction * 73f)).toInt().coerceIn(22, 95)
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(progress = percent, message = "Rendering cinematic video…")
                }
                notification.updateProgress(percent)
            }

            if (!stagedOutput.isFile || stagedOutput.length() < 1024L) {
                throw CinematicVideoException("FFmpeg finished without creating a valid MP4 output")
            }

            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(progress = 97, message = "Saving video…")
            }
            notification.updateProgress(97)

            deleteChildIfPresent(novelFolder, videoName)
            outputUri = DocumentsContract.createDocument(
                context.contentResolver,
                novelFolder,
                MIME_MP4,
                videoName,
            ) ?: throw CinematicVideoException("Could not create MP4 in the selected NoveLA folder")
            copyFileToUri(stagedOutput, outputUri!!)

            val displayName = queryDisplayName(outputUri!!) ?: videoName
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.SUCCESS,
                    progress = 100,
                    message = "",
                    displayName = displayName,
                    documentUri = outputUri.toString(),
                )
            }
            notification.updateProgress(100)
            notification.showComplete(displayName, outputUri)
            workDir.deleteRecursively()
            return Result.success()
        } catch (e: CancellationException) {
            outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            workDir.deleteRecursively()
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(status = TtsAudioJobStatus.CANCELLED, message = "Cancelled")
            }
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsCinematicVideo: failed for $jobId")
            outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            workDir.deleteRecursively()
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.FAILED,
                    message = e.message ?: "Video rendering failed",
                )
            }
            notification.showError(e.message ?: "Video rendering failed")
            return Result.failure()
        }
    }

    private suspend fun setForegroundSafely(notification: TtsAudioExportNotification) {
        try {
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            setForeground(
                ForegroundInfo(
                    notification.notificationId,
                    notification.foregroundNotification(),
                    foregroundType,
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "TtsCinematicVideo: setForeground failed")
        }
    }

    private suspend fun resolveNovelFolder(outputDirectoryUri: String, novelTitle: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = Uri.parse(outputDirectoryUri)
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val wrapperId = findOrCreateDirectoryDocId(
                    treeUri,
                    rootDocId,
                    TtsAudioExportWorker.WRAPPER_FOLDER_NAME,
                ) ?: return@runCatching null
                val novelId = findOrCreateDirectoryDocId(
                    treeUri,
                    wrapperId,
                    sanitize(novelTitle, "novel"),
                ) ?: return@runCatching null
                DocumentsContract.buildDocumentUriUsingTree(treeUri, novelId)
            }.getOrNull()
        }

    private fun findOrCreateDirectoryDocId(treeUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    cursor.getString(nameIndex).equals(name, ignoreCase = true)
                ) return cursor.getString(idIndex)
            }
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )?.let(DocumentsContract::getDocumentId)
        }.getOrNull()
    }

    private fun findExistingAudio(parent: Uri, prefix: String): Uri? {
        val extensions = arrayOf("wav", "m4a", "mp3", "aac", "ogg", "opus")
        return extensions.firstNotNullOfOrNull { extension ->
            findChild(parent, "$prefix.$extension")
        }
    }

    private fun findChild(parent: Uri, displayName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == displayName) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(
                        parent,
                        cursor.getString(idIndex),
                    )
                }
            }
            null
        }
    }

    private fun deleteChildIfPresent(parent: Uri, displayName: String) {
        findChild(parent, displayName)?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    private fun copyUriToFile(uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.parentFile?.mkdirs()
            file.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
        } ?: throw CinematicVideoException("Unable to read downloaded audio/timeline")
    }

    private fun copyFileToUri(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER) }
        } ?: throw CinematicVideoException("Unable to write generated MP4")
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sanitize(name: String, fallback: String = "chapter"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }

    private fun fetchChapterText(
        database: AppDatabase,
        prefs: AppPreferences,
        request: VideoRequest,
    ): String? {
        return if (request.source == TtsAudioSource.TRANSLATED) {
            val sourceLang = request.translationSourceLang.ifBlank { prefs.translationPairForBook(request.novelUrl).source }
            val targetLang = request.translationTargetLang.ifBlank { prefs.translationPairForBook(request.novelUrl).target }
            if (sourceLang.isBlank() || targetLang.isBlank()) return null
            val translation = database.chapterTranslationDao()
                .getTranslations(request.chapterUrl, sourceLang, targetLang)
                ?: return null
            if (translation.translatedParagraphs.isBlank()) return null
            runCatching {
                val paragraphs = JSONArray(translation.translatedParagraphs)
                if (paragraphs.length() == 0) null
                else (0 until paragraphs.length()).joinToString("\n\n") { paragraphs.getString(it) }
            }.getOrNull()
        } else {
            database.chapterBodyDao().get(request.chapterUrl)?.body?.takeIf { it.isNotBlank() }
        }
    }

    private fun hasTranslation(database: AppDatabase, prefs: AppPreferences, request: VideoRequest): Boolean =
        fetchChapterText(database, prefs, request)?.isNotBlank() == true

    private fun readRequest(): VideoRequest? {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return null
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE) ?: return null
        val novelUrl = inputData.getString(KEY_NOVEL_URL) ?: return null
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return null
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: return null
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, 0)
        val source = runCatching { TtsAudioSource.valueOf(inputData.getString(KEY_SOURCE) ?: return null) }.getOrNull() ?: return null
        val outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null
        val sourceLang = inputData.getString(KEY_TRANSLATION_SOURCE_LANG) ?: ""
        val targetLang = inputData.getString(KEY_TRANSLATION_TARGET_LANG) ?: ""
        return VideoRequest(
            jobId = jobId,
            novelTitle = novelTitle,
            novelUrl = novelUrl,
            chapterUrl = chapterUrl,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            source = source,
            outputDirectoryUri = outputDirectoryUri,
            translationSourceLang = sourceLang,
            translationTargetLang = targetLang,
        )
    }

    private data class VideoRequest(
        val jobId: String,
        val novelTitle: String,
        val novelUrl: String,
        val chapterUrl: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val source: TtsAudioSource,
        val outputDirectoryUri: String,
        val translationSourceLang: String,
        val translationTargetLang: String,
    ) {
        val preferredAudioExtension: String get() = TtsAudioFormat.WAV

        fun toExportRequest(): TtsAudioExportRequest = TtsAudioExportRequest(
            jobId = jobId,
            novelTitle = novelTitle,
            novelUrl = novelUrl,
            chapterUrl = chapterUrl,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            source = source,
            enginePackage = "",
            voiceId = "",
            speed = 1f,
            pitch = 1f,
            outputDirectoryUri = outputDirectoryUri,
            format = TtsAudioFormat.WAV,
            translationSourceLang = translationSourceLang,
            translationTargetLang = translationTargetLang,
        )
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_NOVEL_TITLE = "novel_title"
        const val KEY_NOVEL_URL = "novel_url"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_SOURCE = "source"
        const val KEY_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val KEY_TRANSLATION_SOURCE_LANG = "translation_source_lang"
        const val KEY_TRANSLATION_TARGET_LANG = "translation_target_lang"
        const val MIME_MP4 = "video/mp4"
        private const val DEFAULT_BUFFER = 128 * 1024
    }
}
