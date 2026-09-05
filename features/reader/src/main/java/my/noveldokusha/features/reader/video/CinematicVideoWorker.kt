package my.noveldokusha.features.reader.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

internal class CinematicVideoWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val wavUri = inputData.getString(KEY_WAV_URI)?.let(Uri::parse)
            ?: return Result.failure(errorData("Missing WAV URI"))
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.let(Uri::parse)
            ?: return Result.failure(errorData("Missing timeline URI"))
        val outputUri = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
            ?: return Result.failure(errorData("Missing output URI"))

        setForeground(createForegroundInfo(0))

        val tempDir = File(applicationContext.cacheDir, "cinematic-worker").apply { mkdirs() }
        val wavFile = File(tempDir, "input.wav")
        val timelineFile = File(tempDir, "timeline.json")
        val encodedFile = File.createTempFile("noveLa_", ".mp4", tempDir)

        try {
            applicationContext.contentResolver.openInputStream(wavUri).use { input ->
                requireNotNull(input) { "Cannot open WAV URI" }
                wavFile.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            applicationContext.contentResolver.openInputStream(timelineUri).use { input ->
                requireNotNull(input) { "Cannot open timeline URI" }
                timelineFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }

            var lastProgress = -1
            CinematicVideoExporter(applicationContext).export(
                wavFile = wavFile,
                timelineFile = timelineFile,
                outputFile = encodedFile,
            ) { progress ->
                val percent = (progress * 100f).toInt().coerceIn(0, 100)
                if (percent != lastProgress) {
                    lastProgress = percent
                    setProgress(workDataOf(KEY_PROGRESS to percent))
                }
            }

            applicationContext.contentResolver.openOutputStream(outputUri, "w").use { output ->
                requireNotNull(output) { "Cannot open destination URI" }
                encodedFile.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            }
            setProgress(workDataOf(KEY_PROGRESS to 100))
            Result.success(workDataOf(KEY_OUTPUT_URI to outputUri.toString()))
        } catch (t: Throwable) {
            Result.failure(errorData(t.message ?: t.javaClass.simpleName))
        } finally {
            wavFile.delete()
            timelineFile.delete()
            encodedFile.delete()
        }
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NoveLA video export",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Creating cinematic video")
            .setContentText("$progress%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_WAV_URI = "wav_uri"
        const val KEY_TIMELINE_URI = "timeline_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "novela_cinematic_video"
        private const val NOTIFICATION_ID = 0x4E56
    }
}
