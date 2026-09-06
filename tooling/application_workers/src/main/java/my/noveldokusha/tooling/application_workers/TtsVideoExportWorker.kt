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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
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
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val parentDirectoryUri = inputData.getString(KEY_PARENT_DIRECTORY_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "chapter.wav"
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()

        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val renderWav = File(tempDir, "$jobId-video.wav")
        val renderJson = File(tempDir, "$jobId-video.timeline.json")
        val tempVideo = File(tempDir, "$jobId-video.mp4.tmp")
        var videoUri: Uri? = null
        try {
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(status = TtsAudioJobStatus.RUNNING, phase = "VIDEO", progress = 0, documentUri = audioUri)
            }

            copyUriToFile(Uri.parse(audioUri), renderWav)
            copyUriToFile(Uri.parse(timelineUri), renderJson)
            ensureActive()

            CinematicVideoExporter(context).export(
                wavFile = renderWav,
                timelineFile = renderJson,
                outputFile = tempVideo,
                onProgress = { fraction ->
                    val percent = (fraction * 100f).toInt().coerceIn(0, 100)
                    TtsAudioQueue.updateState(prefs, jobId) {
                        it?.copy(status = TtsAudioJobStatus.RUNNING, phase = "VIDEO", progress = percent, documentUri = audioUri)
                    }
                },
                onSizeBytes = { bytes ->
                    TtsAudioQueue.updateState(prefs, jobId) { it?.copy(phase = "VIDEO", videoSizeBytes = bytes) }
                },
            )
            ensureActive()

            val parentUri = Uri.parse(parentDirectoryUri)
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
            return Result.success()
        } catch (e: CancellationException) {
            runCatching { videoUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderWav.delete(); renderJson.delete(); tempVideo.delete()
            restoreAudioState(prefs, jobId, audioUri, displayName)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsVideo: video generation failed for $jobId")
            runCatching { videoUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderWav.delete(); renderJson.delete(); tempVideo.delete()
            restoreAudioState(prefs, jobId, audioUri, displayName, e.message ?: "")
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

    private suspend fun ensureActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_TIMELINE_URI = "timelineUri"
        const val KEY_PARENT_DIRECTORY_URI = "parentDirectoryUri"
        const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_SOURCE = "source"
        private const val MIME_MP4 = "video/mp4"
    }
}
