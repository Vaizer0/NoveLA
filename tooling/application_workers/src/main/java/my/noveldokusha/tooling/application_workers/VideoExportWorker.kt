package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.VideoExportJobStatus
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.reader_visuals.ReaderBackgroundResolver
import my.noveldokusha.reader_visuals.ReaderFontResolver
import my.noveldokusha.reader_visuals.ReaderVisualSnapshot
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.text_to_speech.TtsExportException
import my.noveldokusha.video_export.SyncProbe
import my.noveldokusha.video_export.TtsTimelineCollector
import my.noveldokusha.video_export.VideoEncoder
import my.noveldokusha.video_export.VideoFrameRenderer
import my.noveldokusha.video_export.VideoStyleSettings
import my.noveldokusha.video_export.VideoStyleSnapshot
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Экспорт главы в MP4 (H.264+AAC) в выбранную через SAF папку.
 *
 * Этапы:
 *  1. Прогон аудио + точные тайминги слов → tempWav + VideoExportTimeline
 *  2. Рендер кадров на входной Surface кодека (VideoFrameRenderer)
 *  3. Мультиплексирование MP4 через MediaMuxer (VideoEncoder)
 *  4. Копирование tempMp4 → SAF через contentResolver
 *
 * Параграфы передаются через WorkManager input (paragraphs JSON) на момент enqueue.
 * Контролируется [VideoExportQueue]; UI читает [AppPreferences.VIDEO_EXPORT_JOBS].
 */
class VideoExportWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VideoExportEntryPoint {
        fun appPreferences(): AppPreferences
        fun notificationsCenter(): NotificationsCenter
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()

        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: ""
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE) ?: ""
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return Result.failure()
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: ""
        val paragraphsJson = inputData.getString(KEY_PARAGRAPHS_JSON) ?: return Result.failure()
        val snapshotJson = inputData.getString(KEY_SNAPSHOT_JSON) ?: return Result.failure()
        val enginePackage = inputData.getString(KEY_ENGINE_PACKAGE) ?: ""
        val voiceId = inputData.getString(KEY_VOICE_ID) ?: ""
        val speed = inputData.getFloat(KEY_SPEED, 1f)
        val pitch = inputData.getFloat(KEY_PITCH, 1f)
        val outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: ""

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            VideoExportEntryPoint::class.java
        )
        val appPreferences = entryPoint.appPreferences()
        val notificationsCenter = entryPoint.notificationsCenter()

        val notification = VideoExportNotification(chapterTitle, context, notificationsCenter)

        val paragraphs = runCatching {
            val arr = JSONArray(paragraphsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrNull() ?: return Result.failure()

        if (paragraphs.isEmpty()) {
            fail(appPreferences, jobId, notification, "No paragraphs")
            return Result.failure()
        }

        val tempDir = File(context.cacheDir, "tts_video").apply { mkdirs() }
        val tempWav = File(tempDir, "$jobId.wav")
        val tempMp4 = File(tempDir, "$jobId.mp4")
        var createdUri: Uri? = null

        try {
            if (outputDirectoryUri.isBlank()) {
                fail(appPreferences, jobId, notification,
                    context.getString(StringsR.string.tts_video_export_dir_error))
                return Result.failure()
            }
            if (!isDirectoryAccessible(context, outputDirectoryUri)) {
                Timber.e("VideoExport: directory NOT accessible, clearing VIDEO_DIRECTORY_URI")
                appPreferences.VIDEO_DIRECTORY_URI.value = ""
                fail(appPreferences, jobId, notification,
                    context.getString(StringsR.string.tts_video_export_dir_error))
                return Result.failure()
            }

            val novelFolderUri = resolveNovelFolder(context, outputDirectoryUri, novelTitle)
            if (novelFolderUri == null) {
                Timber.e("VideoExport: could not create novel folder for $jobId")
                fail(appPreferences, jobId, notification,
                    context.getString(StringsR.string.tts_video_export_folder_error))
                return Result.failure()
            }

            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            try {
                setForeground(
                    ForegroundInfo(notification.notificationId, notification.foregroundNotification(), foregroundType)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "VideoExport: setForeground failed, continuing as background worker")
            }

            VideoExportQueue.updateState(appPreferences, jobId) { it!!.copy(status = VideoExportJobStatus.RUNNING) }

            // ── Stage 1: TTS synthesis + timeline collection ─────────────────────
            val report = progressReporter(appPreferences, jobId, notification)
            val destWav = tempWav
            val (wav, timeline) = TtsTimelineCollector(context).collectTimeline(
                displayParagraphs = paragraphs,
                enginePackage = enginePackage,
                voiceId = voiceId,
                speed = speed,
                pitch = pitch,
                destFile = destWav,
                titleText = chapterTitle,
                onProgress = { fraction -> report((fraction * 40).toInt().coerceIn(0, 39)) },
            )

            // Diagnostics: verify the timeline sample-space and audio length agree.
            // If totalSamples is wildly larger than the real WAV (bytes-vs-samples,
            // or a synthesis-scale bug), the renderer freezes and the duration blows up.
            val wavTotal = runCatching {
                my.noveldokusha.video_export.WavPcmSource(wav).use {
                    it.totalSamples to it.sampleRate
                }
            }.getOrNull()
            Timber.w(
                "VideoExport(timeline): paragraphs=${timeline.paragraphs.size} " +
                    "titleEnabled=${timeline.title != null} totalSamples=${timeline.totalSamples} " +
                    "sampleRate=${timeline.sampleRate} ch=${timeline.channelCount} " +
                    "wavTotalSamples=${wavTotal?.first} wavRate=${wavTotal?.second} " +
                    "approxSec=${timeline.totalSamples / timeline.sampleRate}"
            )

            // ── Stage 2: Renderer construction ──────────────────────────────────
            val readerSnapshot = ReaderVisualSnapshot.fromJson(snapshotJson)
            ReaderFontResolver.init(context)
            val typeface = ReaderFontResolver.getTypeFaceNORMAL(readerSnapshot.fontFamily)
            ReaderBackgroundResolver.init(context)

            val appCardColors = runCatching { VideoFrameRenderer.resolveThemeCardColors(context) }
                .getOrNull()
            val videoStyleSettingsJson = appPreferences.VIDEO_STYLE_SETTINGS_JSON.value
            val videoStyle = runCatching {
                VideoStyleSettings.fromJson(videoStyleSettingsJson)
            }.getOrNull()?.resolve(readerSnapshot, appCardColors)
                ?: VideoStyleSnapshot.defaultFor(readerSnapshot)

            val renderer = VideoFrameRenderer(
                snapshot = readerSnapshot,
                timeline = timeline,
                typeface = typeface,
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                backgroundFileResolver = ReaderBackgroundResolver::resolveFile,
                videoStyle = videoStyle,
            )

            // ── Stage 3: A/V sync probe + encode ────────────────────────────────
            report(40)
            val aacPrimingOffsetUs = runCatching {
                SyncProbe.measureAacPrimingOffsetUs(timeline.sampleRate, timeline.channelCount)
            }.getOrDefault(0L)

            VideoEncoder().encode(
                wav = wav,
                timeline = timeline,
                renderer = renderer,
                output = tempMp4,
                aacPrimingOffsetUs = aacPrimingOffsetUs,
            ) { fraction -> report(40 + (fraction * 40).toInt().coerceIn(0, 39)) }

            logMp4Duration(tempMp4)

            val tempMp4Size = tempMp4.length()

            // ── Stage 4: SAF write ──────────────────────────────────────────────
            val sourceSuffix = when (sourceId) {
                "translated" -> context.getString(StringsR.string.tts_audio_file_suffix_translated)
                else -> context.getString(StringsR.string.tts_audio_file_suffix_original)
            }
            val baseName = "${chapterTitle.ifBlank { "chapter" }}"
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)
            val fileName = "$baseName $sourceSuffix.mp4"
            val parentUri = novelFolderUri
            createdUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, parentUri, MIME_MP4, fileName)
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_video_export_file_error))

            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(createdUri!!)
                    ?: throw TtsExportException(context.getString(StringsR.string.tts_video_export_file_error))
                output.use { os ->
                    tempMp4.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            os.write(buffer, 0, read)
                            copied += read
                            if (tempMp4Size > 0) {
                                report(
                                    80 + ((copied * 10) / tempMp4Size).toInt().coerceIn(0, 9)
                                )
                            }
                        }
                    }
                }
            }

            val displayName = queryDisplayName(createdUri!!) ?: fileName

            report(100)
            notification.updateProgress(100)
            notification.showComplete(displayName, createdUri)
            VideoExportQueue.updateState(appPreferences, jobId) {
                it!!.copy(
                    status = VideoExportJobStatus.SUCCESS,
                    displayName = displayName,
                    documentUri = createdUri.toString(),
                    progress = 100,
                )
            }
            tempWav.delete()
            tempMp4.delete()
            return Result.success()
        } catch (e: CancellationException) {
            createdUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            tempWav.delete()
            tempMp4.delete()
            VideoExportQueue.updateState(appPreferences, jobId) {
                it!!.copy(status = VideoExportJobStatus.CANCELLED, message = "Cancelled")
            }
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "VideoExport: EXPORT FAILED for $jobId")
            createdUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            tempWav.delete()
            tempMp4.delete()
            val message = context.getString(StringsR.string.tts_video_export_failure_detail, e.message ?: "")
            notification.showError(message)
            VideoExportQueue.updateState(appPreferences, jobId) {
                it!!.copy(status = VideoExportJobStatus.FAILED, message = e.message ?: "")
            }
            return Result.failure()
        }
    }

    private fun fail(
        appPreferences: AppPreferences,
        jobId: String,
        notification: VideoExportNotification,
        message: String,
    ) {
        notification.showError(message)
        VideoExportQueue.updateState(appPreferences, jobId) {
            it!!.copy(status = VideoExportJobStatus.FAILED, message = message)
        }
    }

    private fun progressReporter(
        appPreferences: AppPreferences,
        jobId: String,
        notification: VideoExportNotification,
    ): (Int) -> Unit {
        var lastReported = -1
        var lastNotifyMs = 0L
        return report@{ percent ->
            val clamped = percent.coerceIn(0, 100)
            if (clamped == lastReported) return@report
            lastReported = clamped
            Timber.d("VideoExport progress $clamped%")
            runCatching { setProgressAsync(workDataOf(KEY_PROGRESS to clamped)) }
            VideoExportQueue.updateState(appPreferences, jobId) {
                it!!.copy(progress = clamped)
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNotifyMs >= 1_000 || clamped == 100) {
                notification.updateProgress(clamped)
                lastNotifyMs = now
            }
        }
    }

    private fun isDirectoryAccessible(context: Context, directoryUri: String): Boolean {
        return try {
            val treeUri = Uri.parse(directoryUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { true } ?: false
        } catch (e: Exception) {
            Timber.e(e, "VideoExport: isDirectoryAccessible FAILED")
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Независимый пост-фактум контроль: читает длительности дорожек готового MP4
     * через MediaExtractor и логирует их. Это диагностика огромной (59:39:08)
     * длительности — сюда смотрится логатель, чтобы понять, откуда берётся цифра.
     */
    private fun logMp4Duration(mp4: File) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(mp4.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: "?"
                    // MediaFormat.KEY_DURATION даёт длительность дорожки в мкс.
                    val dur = if (f.containsKey(android.media.MediaFormat.KEY_DURATION))
                        f.getLong(android.media.MediaFormat.KEY_DURATION)
                    else -1L
                    Timber.w("VideoExport(mp4): track $i mime=$mime durationUs=$dur sec=${dur / 1_000_000L}")
                }
                val max = (0 until extractor.trackCount).mapNotNull { i ->
                    val f = extractor.getTrackFormat(i)
                    if (f.containsKey(android.media.MediaFormat.KEY_DURATION))
                        f.getLong(android.media.MediaFormat.KEY_DURATION) else null
                }.maxOrNull()
                Timber.w("VideoExport(mp4): level maxDurationUs=$max sec=${(max ?: 0L) / 1_000_000L}")
            } finally {
                runCatching { extractor.release() }
            }
        }.onFailure { Timber.e(it, "VideoExport(mp4): could not read duration") }
    }

    private fun sanitize(name: String, fallback: String = "novel"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }

    private suspend fun resolveNovelFolder(
        context: Context,
        outputDirectoryUri: String,
        novelTitle: String,
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = Uri.parse(outputDirectoryUri)
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val wrapperDocId = findOrCreateDirectoryDocId(
                context = context,
                treeUri = treeUri,
                parentDocId = rootDocId,
                folderName = WRAPPER_FOLDER_NAME,
            ) ?: return@runCatching null

            val novelFolderName = sanitize(novelTitle, fallback = "novel")
            val novelDocId = findOrCreateDirectoryDocId(
                context = context,
                treeUri = treeUri,
                parentDocId = wrapperDocId,
                folderName = novelFolderName,
            ) ?: return@runCatching null

            DocumentsContract.buildDocumentUriUsingTree(treeUri, novelDocId)
        }.getOrNull()
    }

    private fun findOrCreateDirectoryDocId(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        folderName: String,
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val existing = context.contentResolver.query(childrenUri, null, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    )
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    )
                    if (displayName.equals(folderName, ignoreCase = true)) {
                        return@use cursor.getString(
                            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        )
                    }
                }
                null
            }
        if (existing != null) return existing

        val createdDocId = runCatching {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName,
            )?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull()

        if (createdDocId != null) return createdDocId

        return context.contentResolver.query(childrenUri, null, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    )
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    )
                    if (displayName.equals(folderName, ignoreCase = true)) {
                        return@use cursor.getString(
                            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        )
                    }
                }
                null
            }
    }

    companion object {
        const val TAG = "VideoExport"
        const val MIME_MP4 = "video/mp4"
        const val WRAPPER_FOLDER_NAME = "NoveLA Videos"
        const val KEY_PROGRESS = "progress"
        const val KEY_JOB_ID = "job_id"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_NOVEL_TITLE = "novel_title"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_PARAGRAPHS_JSON = "paragraphs_json"
        const val KEY_SNAPSHOT_JSON = "snapshot_json"
        const val KEY_ENGINE_PACKAGE = "engine_package"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch"
        const val KEY_OUTPUT_DIRECTORY_URI = "output_directory_uri"
    }
}