package my.noveldokusha.tooling.application_workers

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.text_to_speech.AudiobookExportRequest
import my.noveldokusha.text_to_speech.AudiobookExportProgress
import my.noveldokusha.text_to_speech.AudiobookTtsExporter
import my.noveldokusha.text_to_speech.OutputFormat
import my.noveldokusha.text_to_speech.buildOriginalAudiobookChapter
import my.noveldokusha.text_to_speech.buildTranslatedAudiobookChapter
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.util.UUID

class AudiobookExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun appDatabase(): AppDatabase
        fun appPreferences(): AppPreferences
    }

    companion object {
        const val TAG = "AudiobookExport"
        private const val BOOK_URL = "book_url"
        private const val BOOK_TITLE = "book_title"
        private const val MODE = "mode"
        private const val SOURCE = "source"
        private const val TARGET = "target"
        private const val START = "start"
        private const val END = "end"
        private const val ENGINE = "engine"
        private const val VOICE = "voice"
        private const val SPEED = "speed"
        private const val PITCH = "pitch"
        private const val FORMAT = "format"
        private const val VISUAL = "visual"
        private const val DIRECTORY = "directory"

        fun enqueue(
            context: Context,
            bookUrl: String,
            request: AudiobookExportRequest,
            directoryUri: String,
        ) {
            val data = workDataOf(
                BOOK_URL to request.bookTitle.substringBefore('\u0000'),
                BOOK_TITLE to request.bookTitle.substringAfter('\u0000'),
                MODE to request.contentMode,
                SOURCE to request.sourceLang,
                TARGET to request.targetLang,
                START to request.startPosition,
                END to request.endPosition,
                ENGINE to request.enginePackage,
                VOICE to request.voiceId,
                SPEED to request.speed,
                PITCH to request.pitch,
                FORMAT to request.outputFormat.name,
                VISUAL to (request.visualUri?.toString() ?: ""),
                DIRECTORY to directoryUri,
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AudiobookExportWorker>()
                    .setInputData(data)
                    .build()
            )
        }

        fun cancelTask(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            Entry::class.java,
        )
        val db = entry.appDatabase()
        val prefs = entry.appPreferences()

        val bookUrl = inputData.getString(BOOK_URL) ?: return Result.failure()
        val bookTitle = inputData.getString(BOOK_TITLE) ?: return Result.failure()
        val mode = inputData.getString(MODE) ?: "original"
        val sourceLang = inputData.getString(SOURCE).orEmpty()
        val targetLang = inputData.getString(TARGET).orEmpty()
        val start = inputData.getInt(START, Int.MIN_VALUE)
        val end = inputData.getInt(END, Int.MIN_VALUE)
        val format = runCatching { OutputFormat.valueOf(inputData.getString(FORMAT) ?: "WAV") }.getOrDefault(OutputFormat.WAV)
        val visual = inputData.getString(VISUAL).orEmpty().takeIf { it.isNotBlank() }?.let(Uri::parse)
        val directory = inputData.getString(DIRECTORY).orEmpty()
        if (start == Int.MIN_VALUE || end == Int.MIN_VALUE || directory.isBlank()) return Result.failure()
        if (!isDirectoryAccessible(directory)) return Result.failure()

        val chapters = db.chapterDao().chapters(bookUrl)
            .filter { it.position in start..end }
            .sortedBy { it.position }
        if (chapters.size != end - start + 1) {
            Timber.w("Audiobook: requested range is not contiguous")
            return Result.failure()
        }

        val chapterData = if (mode == "translation") {
            require(sourceLang.isNotBlank() && targetLang.isNotBlank())
            val translations = db.chapterTranslationDao()
                .getTranslationsByChapterUrls(chapters.map(Chapter::url), sourceLang, targetLang)
                .associateBy { it.chapterUrl }
            chapters.map { chapter ->
                val tr = translations[chapter.url] ?: error("Missing translation: " + chapter.title)
                val paragraphsJson = JSONArray(tr.translatedParagraphs)
                val paragraphs = buildList {
                    for (i in 0 until paragraphsJson.length()) {
                        val text = paragraphsJson.optString(i, "")
                        if (text.isNotBlank()) add(text)
                    }
                }
                buildTranslatedAudiobookChapter(
                    position = chapter.position,
                    url = chapter.url,
                    originalTitle = chapter.title,
                    translatedTitle = tr.titleTranslation,
                    translatedParagraphs = paragraphs,
                    bookTitle = bookTitle,
                )
            }
        } else {
            val bodies = db.chapterBodyDao()
                .getBodiesByUrls(chapters.map(Chapter::url))
                .associateBy { it.url }
            chapters.map { chapter ->
                val body = bodies[chapter.url]?.body ?: error("Missing chapter body: " + chapter.title)
                buildOriginalAudiobookChapter(
                    position = chapter.position,
                    url = chapter.url,
                    title = chapter.title,
                    body = body,
                    bookTitle = bookTitle,
                )
            }
        }

        val engine = inputData.getString(ENGINE).orEmpty()
        val voice = inputData.getString(VOICE).orEmpty()
        val speed = inputData.getFloat(SPEED, prefs.READER_TEXT_TO_SPEECH_VOICE_SPEED.value)
        val pitch = inputData.getFloat(PITCH, prefs.READER_TEXT_TO_SPEECH_VOICE_PITCH.value)

        val request = AudiobookExportRequest(
            bookTitle = bookTitle,
            contentMode = mode,
            sourceLang = sourceLang,
            targetLang = targetLang,
            startPosition = start,
            endPosition = end,
            enginePackage = engine,
            voiceId = voice,
            speed = speed,
            pitch = pitch,
            outputFormat = format,
            visualUri = visual,
        )

        val id = UUID.randomUUID().toString()
        val tempDir = File(applicationContext.cacheDir, "audiobook-" + id)
        tempDir.mkdirs()
        val audioTemp = File(tempDir, "audio." + if (format == OutputFormat.WAV) "wav" else "mp4")
        val finalMp4 = File(tempDir, "final.mp4")
        val jsonTemp = File(tempDir, "metadata.json")
        val notification = AudiobookExportNotification(bookTitle, applicationContext)

        try {
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            setForeground(ForegroundInfo(
                notification.notificationId,
                notification.foregroundNotification(chapters.size),
                foregroundType,
            ))

            val exporter = AudiobookTtsExporter(applicationContext)
            val durationMs = exporter.export(
                request = request,
                chapters = chapterData,
                mediaFile = audioTemp,
                jsonFile = jsonTemp,
            ) { progress ->
                notification.showProgress(progress)
                setProgress(
                    workDataOf(
                        "chapter" to progress.currentChapter,
                        "total" to progress.totalChapters,
                        "title" to progress.chapterTitle,
                    )
                )
            }

            val outputMedia = if (format == OutputFormat.MP4) {
                exporter.muxVisual(
                    audioMp4 = audioTemp,
                    outputMp4 = finalMp4,
                    visualUri = visual,
                    durationMs = durationMs,
                )
                finalMp4
            } else audioTemp

            val finalMediaName = buildFileName(bookTitle, start, end, mode, targetLang, format)
            val finalJsonName = finalMediaName.substringBeforeLast('.') + ".json"
            val mediaUri = createAndCopy(directory, finalMediaName, if (format == OutputFormat.MP4) "video/mp4" else "audio/wav", outputMedia)
            createAndCopy(directory, finalJsonName, "application/json", jsonTemp)

            notification.showComplete(finalMediaName, mediaUri)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            notification.close()
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Audiobook export failed")
            notification.showError(e.message ?: "Audiobook export failed")
            Result.failure()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun isDirectoryAccessible(uriString: String): Boolean = runCatching {
        val tree = Uri.parse(uriString)
        val doc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        applicationContext.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { true } ?: false
    }.getOrElse { false }

    private fun createAndCopy(
        directoryUri: String,
        displayName: String,
        mime: String,
        source: File,
    ): Uri {
        val resolver = applicationContext.contentResolver
        val tree = Uri.parse(directoryUri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val uri = DocumentsContract.createDocument(resolver, doc, mime, displayName)
            ?: error("Unable to create " + displayName)
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: error("Unable to open " + displayName)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun buildFileName(
        title: String,
        start: Int,
        end: Int,
        mode: String,
        target: String,
        format: OutputFormat,
    ): String {
        val suffix = if (mode == "translation" && target.isNotBlank()) target else "Original"
        val extension = if (format == OutputFormat.WAV) "wav" else "mp4"
        return sanitize(title) + "_Chapters_" +
            start.toString().padStart(3, '0') + "-" +
            end.toString().padStart(3, '0') + "_" +
            sanitize(suffix) + "." + extension
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(80)
            .ifBlank { "audiobook" }
}
