package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.tooling.application_workers.video.CinematicVideoExporter
import timber.log.Timber
import java.io.File

/** Non-destructive VIDEO stage. It consumes durable WAV+timeline and never deletes them. */
class TtsVideoExportWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
        fun notificationsCenter(): NotificationsCenter
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val outputTree = inputData.getString(KEY_OUTPUT_DIRECTORY_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: ""
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "chapter.wav"
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java)
        val prefs = entry.appPreferences()
        val notification = TtsAudioExportNotification(
            chapterTitle = chapterTitle,
            workRequestId = id.toString(),
            context = context,
            notificationsCenter = entry.notificationsCenter(),
        )

        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val renderWav = File(tempDir, "$jobId-video.wav")
        val renderJson = File(tempDir, "$jobId-video.timeline.json")
        val tempVideo = File(tempDir, "$jobId-video.mp4.tmp")
        var videoUri: Uri? = null
        try {
            TtsAudioQueue.updateState(prefs, jobId) { it?.copy(status = TtsAudioJobStatus.RUNNING, phase = "VIDEO", progress = 0, documentUri = audioUri) }
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            runCatching { setForeground(ForegroundInfo(notification.notificationId, notification.videoForegroundNotification(), foregroundType)) }
                .onFailure { Timber.w(it, "TtsVideo: setForeground failed") }

            copyUriToFile(Uri.parse(audioUri), renderWav)
            copyUriToFile(Uri.parse(timelineUri), renderJson)
            ensureActive()
            deleteExpectedVideo(Uri.parse(outputTree), Uri.parse(audioUri), displayName)

            CinematicVideoExporter(context).export(
                wavFile = renderWav,
                timelineFile = renderJson,
                outputFile = tempVideo,
                onProgress = { fraction ->
                    val percent = (fraction * 100f).toInt().coerceIn(0, 100)
                    TtsAudioQueue.updateState(prefs, jobId) { it?.copy(status = TtsAudioJobStatus.RUNNING, phase = "VIDEO", progress = percent, documentUri = audioUri) }
                    notification.updateProgress(percent, "VIDEO")
                },
                onSizeBytes = { bytes ->
                    TtsAudioQueue.updateState(prefs, jobId) { it?.copy(phase = "VIDEO", videoSizeBytes = bytes) }
                },
            )
            ensureActive()

            val parentId = queryParentId(Uri.parse(audioUri))
                ?: throw IllegalStateException("Cannot resolve audio parent directory")
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(outputTree), parentId)
            val videoName = displayName.removeSuffix(".wav") + ".mp4"
            videoUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, parentUri, MIME_MP4, videoName)
            } ?: throw IllegalStateException("Cannot create MP4 document")
            copyFileToUri(tempVideo, videoUri!!)
            ensureActive()

            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.SUCCESS,
                    phase = "VIDEO",
                    progress = 100,
                    documentUri = videoUri.toString(),
                    workRequestId = "",
                    videoSizeBytes = tempVideo.length(),
                    message = "",
                )
            }
            notification.updateProgress(100, "VIDEO")
            notification.close()
            notification.showComplete(videoName, videoUri)
            return Result.success()
        } catch (e: CancellationException) {
            runCatching { videoUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderWav.delete(); renderJson.delete(); tempVideo.delete()
            restoreAudioState(prefs, jobId, audioUri, displayName)
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsVideo: video generation failed for $jobId")
            runCatching { videoUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderWav.delete(); renderJson.delete(); tempVideo.delete()
            restoreAudioState(prefs, jobId, audioUri, displayName, e.message ?: "")
            notification.close()
            return Result.failure()
        } finally {
            renderWav.delete(); renderJson.delete(); tempVideo.delete()
        }
    }

    private suspend fun restoreAudioState(prefs: AppPreferences, jobId: String, audioUri: String, displayName: String, message: String = "") {
        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.SUCCESS,
                phase = "AUDIO",
                progress = 100,
                documentUri = audioUri,
                audioUri = audioUri,
                workRequestId = "",
                videoSizeBytes = 0L,
                displayName = displayName,
                message = message,
            )
        }
    }

    private suspend fun copyUriToFile(uri: Uri, target: File) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("Cannot read $uri")
    }

    private suspend fun copyFileToUri(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("Cannot write $uri")
    }

    private suspend fun deleteExpectedVideo(treeUri: Uri, audioUri: Uri, audioName: String) = withContext(Dispatchers.IO) {
        val parentId = queryParentId(audioUri) ?: return@withContext
        val videoName = audioName.removeSuffix(".wav") + ".mp4"
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(children, null, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol).equals(videoName, true)) {
                    DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol)))
                    break
                }
            }
        }
    }

    private fun queryParentId(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_PARENT_DOCUMENT_ID),
        null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_PARENT_DOCUMENT_ID)) else null
    }

    private suspend fun ensureActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_TIMELINE_URI = "timelineUri"
        const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_SOURCE = "source"
        private const val MIME_MP4 = "video/mp4"
    }
}
