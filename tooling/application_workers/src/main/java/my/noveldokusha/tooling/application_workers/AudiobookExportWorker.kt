package my.noveldokusha.tooling.application_workers

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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

    @Volatile
    private var currentStage: String = "INITIALIZING"


    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun appDatabase(): AppDatabase
        fun appPreferences(): AppPreferences
    }

    companion object {
        const val TAG = "AudiobookExport"
        const val INPUT_BOOK_URL = "book_url"
        const val INPUT_BOOK_TITLE = "book_title"
        const val INPUT_MODE = "mode"
        const val INPUT_SOURCE = "source"
        const val INPUT_TARGET = "target"
        const val INPUT_START = "start"
        const val INPUT_END = "end"
        const val INPUT_ENGINE = "engine"
        const val INPUT_VOICE = "voice"
        const val INPUT_SPEED = "speed"
        const val INPUT_PITCH = "pitch"
        const val INPUT_FORMAT = "format"
        const val INPUT_VISUAL = "visual"
        const val INPUT_DIRECTORY = "directory"
        const val PROGRESS_PERCENT = "percent"
        const val PROGRESS_STAGE = "stage"
        const val OUTPUT_ERROR = "error"
        const val OUTPUT_REQUEST_ID = "requestId"

        private const val BOOK_URL = INPUT_BOOK_URL
        private const val BOOK_TITLE = INPUT_BOOK_TITLE
        private const val MODE = INPUT_MODE
        private const val SOURCE = INPUT_SOURCE
        private const val TARGET = INPUT_TARGET
        private const val START = INPUT_START
        private const val END = INPUT_END
        private const val ENGINE = INPUT_ENGINE
        private const val VOICE = INPUT_VOICE
        private const val SPEED = INPUT_SPEED
        private const val PITCH = INPUT_PITCH
        private const val FORMAT = INPUT_FORMAT
        private const val VISUAL = INPUT_VISUAL
        private const val DIRECTORY = INPUT_DIRECTORY

        fun enqueue(
            context: Context,
            bookUrl: String,
            request: AudiobookExportRequest,
            directoryUri: String,
        ) {
            val data = workDataOf(
                BOOK_URL to bookUrl,
                BOOK_TITLE to request.bookTitle,
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
            val requestWork = OneTimeWorkRequestBuilder<AudiobookExportWorker>()
                .setInputData(data)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(requestWork)
        }

        fun cancelTask(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }

    override suspend fun doWork(): Result {
        val requestId = id.toString()
        var notification: AudiobookExportNotification? = null

        return try {
            currentStage = "INITIALIZE"
            val entry = EntryPointAccessors.fromApplication(
                applicationContext,
                Entry::class.java,
            )
            val db = entry.appDatabase()
            val prefs = entry.appPreferences()

            currentStage = "READ_INPUT"
            val bookUrl = inputData.getString(BOOK_URL)
                ?: error("Missing book URL")
            val bookTitle = inputData.getString(BOOK_TITLE)
                ?: error("Missing book title")
            val mode = inputData.getString(MODE) ?: "original"
            val sourceLang = inputData.getString(SOURCE).orEmpty()
            val targetLang = inputData.getString(TARGET).orEmpty()
            val start = inputData.getInt(START, Int.MIN_VALUE)
            val end = inputData.getInt(END, Int.MIN_VALUE)
            val format = runCatching {
                OutputFormat.valueOf(inputData.getString(FORMAT) ?: "WAV")
            }.getOrElse {
                error("Unsupported audiobook output format")
            }
            val visual = inputData.getString(VISUAL)
                .orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
            val directory = inputData.getString(DIRECTORY).orEmpty()

            notification = AudiobookExportNotification(bookTitle, applicationContext)

            currentStage = "FOREGROUND"
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            setForeground(
                ForegroundInfo(
                    notification!!.notificationId,
                    notification!!.foregroundNotification(0),
                    foregroundType,
                )
            )

            currentStage = "PREFLIGHT"
            setProgress(workDataOf(PROGRESS_PERCENT to 0, PROGRESS_STAGE to "preflight"))
            require(start != Int.MIN_VALUE && end != Int.MIN_VALUE) {
                "Invalid chapter range"
            }
            require(start <= end) {
                "Chapter range is reversed"
            }
            require(directory.isNotBlank()) {
                "No audiobook export folder selected"
            }
            require(isDirectoryAccessible(directory)) {
                "Audiobook export folder is not accessible or writable"
            }
            require(mode == "original" || mode == "translation") {
                "Unsupported audiobook content mode: " + mode
            }
            if (format == OutputFormat.MP4) {
                visual?.let {
                    require(isVisualUriReadable(it)) {
                        "Selected visual cannot be read"
                    }
                }
            }

            currentStage = "LOAD_CHAPTERS"
            val chapters = db.chapterDao()
                .chapters(bookUrl)
                .filter { it.position in start..end }
                .sortedBy { it.position }
            require(chapters.isNotEmpty()) {
                "No chapters found in selected range"
            }
            require(chapters.size == end - start + 1) {
                "Selected chapter range is not contiguous or some chapters are missing"
            }

            currentStage = "LOAD_CONTENT"
            val chapterData = if (mode == "translation") {
                require(sourceLang.isNotBlank() && targetLang.isNotBlank()) {
                    "Translation source/target language is missing"
                }
                val translations = db.chapterTranslationDao()
                    .getTranslationsByChapterUrls(
                        chapters.map(Chapter::url),
                        sourceLang,
                        targetLang,
                    )
                    .associateBy { it.chapterUrl }

                val missing = chapters.filter { it.url !in translations }
                require(missing.isEmpty()) {
                    "Missing translation for chapters: " +
                        missing.joinToString { it.position.toString() }
                }

                chapters.map { chapter ->
                    val tr = translations.getValue(chapter.url)
                    val paragraphsJson = JSONArray(tr.translatedParagraphs)
                    val paragraphs = buildList {
                        for (i in 0 until paragraphsJson.length()) {
                            val text = paragraphsJson.optString(i, "")
                            if (text.isNotBlank()) add(text)
                        }
                    }
                    require(paragraphs.isNotEmpty()) {
                        "Translation has no text for chapter " + chapter.position
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

                val missing = chapters.filter {
                    it.url !in bodies || bodies[it.url]?.body.isNullOrBlank()
                }
                require(missing.isEmpty()) {
                    "Missing chapter body for chapters: " +
                        missing.joinToString { it.position.toString() }
                }

                chapters.map { chapter ->
                    val body = bodies.getValue(chapter.url).body
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
            val speed = inputData.getFloat(
                SPEED,
                prefs.READER_TEXT_TO_SPEECH_VOICE_SPEED.value,
            )
            val pitch = inputData.getFloat(
                PITCH,
                prefs.READER_TEXT_TO_SPEECH_VOICE_PITCH.value,
            )

            currentStage = "SYNTHESIZE_AUDIO"
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

            val tempId = UUID.randomUUID().toString()
            val tempDir = File(applicationContext.cacheDir, "audiobook-" + tempId)
            require(tempDir.mkdirs() || tempDir.isDirectory) {
                "Unable to create temporary audiobook directory"
            }

            val audioTemp = File(
                tempDir,
                "audio." + if (format == OutputFormat.WAV) "wav" else "mp4",
            )
            val finalMp4 = File(tempDir, "final.mp4")
            val jsonTemp = File(tempDir, "metadata.json")
            var lastProgressNotificationMs = 0L

            try {
                val exporter = AudiobookTtsExporter(applicationContext)

                val durationMs = exporter.export(
                    request = request,
                    chapters = chapterData,
                    mediaFile = audioTemp,
                    jsonFile = jsonTemp,
                ) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    val overallPercent = (progress.percent * 90 / 100)
                        .coerceIn(0, 90)
                    if (
                        overallPercent == 90 ||
                        now - lastProgressNotificationMs >= 500L
                    ) {
                        notification!!.showProgress(overallPercent)
                        setProgress(
                            workDataOf(
                                PROGRESS_PERCENT to overallPercent,
                                PROGRESS_STAGE to "audio",
                            )
                        )
                        lastProgressNotificationMs = now
                    }
                }

                require(audioTemp.exists() && audioTemp.length() > 0L) {
                    "Generated audio file is missing or empty"
                }
                require(durationMs > 0L) {
                    "Generated audio duration is zero"
                }

                val outputMedia = if (format == OutputFormat.MP4) {
                    currentStage = "MUX_MP4"
                    notification!!.showFinalizing(90)
                    val videoProgress = AtomicInteger(0)
                    coroutineScope {
                        val progressJob = launch {
                            var last = -1
                            while (isActive) {
                                val p = videoProgress.get().coerceIn(0, 100)
                                if (p != last) {
                                    notification!!.showProgress(90 + p * 10 / 100)
                                    setProgress(
                                        workDataOf(
                                            PROGRESS_PERCENT to (90 + p * 10 / 100),
                                            PROGRESS_STAGE to "video",
                                        )
                                    )
                                    last = p
                                }
                                if (p >= 100) break
                                delay(250L)
                            }
                        }

                        try {
                            exporter.muxVisual(
                                audioMp4 = audioTemp,
                                outputMp4 = finalMp4,
                                visualUri = visual,
                                durationMs = durationMs,
                            ) { videoPercent ->
                                videoProgress.set(videoPercent.coerceIn(0, 100))
                            }
                            videoProgress.set(100)
                        } finally {
                            progressJob.join()
                        }
                    }
                    finalMp4
                } else {
                    audioTemp
                }

                currentStage = "VALIDATE_OUTPUT"
                require(outputMedia.exists() && outputMedia.length() > 0L) {
                    "Audiobook output file is missing or empty"
                }
                require(jsonTemp.exists() && jsonTemp.length() > 0L) {
                    "Audiobook JSON file is missing or empty"
                }

                currentStage = "SAVE_OUTPUT"
                val finalMediaName = buildFileName(
                    bookTitle,
                    start,
                    end,
                    mode,
                    targetLang,
                    format,
                )
                val finalJsonName =
                    finalMediaName.substringBeforeLast('.') + ".json"

                val novelDirectory = getOrCreateNovelDirectory(
                    directory,
                    bookTitle,
                )
                createAndCopy(
                    novelDirectory,
                    finalMediaName,
                    if (format == OutputFormat.MP4) "video/mp4" else "audio/wav",
                    outputMedia,
                )
                createAndCopy(
                    novelDirectory,
                    finalJsonName,
                    "application/json",
                    jsonTemp,
                )

                currentStage = "COMPLETE"
                notification!!.showProgress(100)
                notification!!.showComplete(bookTitle + "/" + finalMediaName)
                Result.success(
                    workDataOf(
                        PROGRESS_PERCENT to 100,
                        PROGRESS_STAGE to "complete",
                        OUTPUT_REQUEST_ID to requestId,
                    )
                )
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: CancellationException) {
            Timber.i(
                "Audiobook export cancelled: id=%s stage=%s",
                requestId,
                currentStage,
            )
            notification?.close()
            throw e
        } catch (e: Throwable) {
            Timber.e(
                e,
                "Audiobook export failed: id=%s stage=%s",
                requestId,
                currentStage,
            )
            notification?.showError(
                currentStage + ": " + (e.message ?: e::class.java.simpleName),
            )
            Result.failure(
                workDataOf(
                    PROGRESS_PERCENT to 0,
                    PROGRESS_STAGE to currentStage,
                    OUTPUT_ERROR to (e.message ?: e::class.java.simpleName),
                    OUTPUT_REQUEST_ID to requestId,
                )
            )
        }
    }

    private fun isVisualUriReadable(uri: Uri): Boolean = runCatching {
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            input.read() >= 0
        } ?: false
    }.getOrElse { false }

    private fun isDirectoryAccessible(uriString: String): Boolean = runCatching {
        val directory = DocumentFile.fromTreeUri(applicationContext, Uri.parse(uriString))
            ?: return@runCatching false
        directory.isDirectory && directory.canWrite()
    }.getOrElse { false }

    private fun getOrCreateNovelDirectory(
        rootUri: String,
        bookTitle: String,
    ): DocumentFile {
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(rootUri))
            ?: error("Unable to open audiobook root folder")
        if (!root.isDirectory || !root.canWrite()) {
            error("Audiobook root folder is not writable")
        }

        val folderName = sanitize(bookTitle).take(80).ifBlank { "audiobook" }
        return root.listFiles()
            .firstOrNull { it.isDirectory && it.name == folderName }
            ?: root.createDirectory(folderName)
            ?: error("Unable to create novel folder: " + folderName)
    }

    private fun createAndCopy(
        directory: DocumentFile,
        displayName: String,
        mime: String,
        source: File,
    ): Uri {
        if (!directory.isDirectory || !directory.canWrite()) {
            error("Novel audiobook folder is not writable")
        }

        directory.listFiles()
            .firstOrNull { it.name == displayName }
            ?.let { existing ->
                if (!existing.delete()) error("Unable to replace existing " + displayName)
            }

        val target = directory.createFile(mime, displayName)
            ?: error("Unable to create " + displayName)
        try {
            applicationContext.contentResolver.openOutputStream(target.uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output, 64 * 1024)
                }
            } ?: error("Unable to open " + displayName)

            if (!target.exists()) {
                error("Exported file is no longer accessible: " + displayName)
            }
        } catch (e: Throwable) {
            runCatching { target.delete() }
            throw e
        }
        return target.uri
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
