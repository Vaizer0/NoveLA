package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsExportMode
import java.util.UUID

/**
 * Single-click cinematic export: synthesize WAV + timeline first, then render MP4
 * from that exact pair. Audio is never synthesized twice.
 */
object TtsCinematicVideoQueue {
    private const val WORK_PREFIX = "tts-cinematic-video"
    const val VIDEO_TAG = "tts-cinematic-video"

    fun enqueue(
        context: Context,
        appPreferences: AppPreferences,
        request: TtsAudioExportRequest,
    ): UUID {
        require(request.exportMode == TtsExportMode.CINEMATIC_VIDEO) {
            "TtsCinematicVideoQueue requires CINEMATIC_VIDEO mode"
        }

        // A generation-specific id prevents late callbacks from an older replacement
        // from overwriting this export's state, matching TtsAudioQueue semantics.
        val generationJobId = "${request.jobId}::${UUID.randomUUID()}"
        val audioRequest = OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
            .setInputData(
                workDataOf(
                    TtsAudioExportWorker.KEY_JOB_ID to generationJobId,
                    TtsAudioExportWorker.KEY_NOVEL_TITLE to request.novelTitle,
                    TtsAudioExportWorker.KEY_NOVEL_URL to request.novelUrl,
                    TtsAudioExportWorker.KEY_CHAPTER_URL to request.chapterUrl,
                    TtsAudioExportWorker.KEY_CHAPTER_TITLE to request.chapterTitle,
                    TtsAudioExportWorker.KEY_CHAPTER_INDEX to request.chapterIndex,
                    TtsAudioExportWorker.KEY_SOURCE to request.source.name,
                    TtsAudioExportWorker.KEY_ENGINE_PACKAGE to request.enginePackage,
                    TtsAudioExportWorker.KEY_VOICE_ID to request.voiceId,
                    TtsAudioExportWorker.KEY_SPEED to request.speed,
                    TtsAudioExportWorker.KEY_PITCH to request.pitch,
                    TtsAudioExportWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                    TtsAudioExportWorker.KEY_FORMAT to request.format,
                    TtsAudioExportWorker.KEY_TRANSLATION_SOURCE_LANG to request.translationSourceLang,
                    TtsAudioExportWorker.KEY_TRANSLATION_TARGET_LANG to request.translationTargetLang,
                )
            )
            .addTag(TtsAudioQueue.AUDIO_TAG)
            .build()

        val videoRequest = OneTimeWorkRequestBuilder<TtsCinematicVideoWorker>()
            .setInputData(
                workDataOf(
                    TtsCinematicVideoWorker.KEY_JOB_ID to generationJobId,
                    TtsCinematicVideoWorker.KEY_NOVEL_TITLE to request.novelTitle,
                    TtsCinematicVideoWorker.KEY_CHAPTER_TITLE to request.chapterTitle,
                    TtsCinematicVideoWorker.KEY_CHAPTER_INDEX to request.chapterIndex,
                    TtsCinematicVideoWorker.KEY_SOURCE to request.source.name,
                    TtsCinematicVideoWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                )
            )
            .addTag(VIDEO_TAG)
            .build()

        TtsAudioQueue.updateState(appPreferences, generationJobId) {
            TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsAudioJobStatus.QUEUED,
                message = "Preparing cinematic video…",
                displayName = "",
                documentUri = "",
                progress = 0,
                workRequestId = audioRequest.id.toString(),
            )
        }

        WorkManager.getInstance(context)
            .beginUniqueWork(
                "$WORK_PREFIX-${request.jobId}",
                ExistingWorkPolicy.REPLACE,
                audioRequest,
            )
            .then(videoRequest)
            .enqueue()

        return audioRequest.id
    }

    fun cancel(context: Context, logicalJobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("$WORK_PREFIX-$logicalJobId")
    }

    suspend fun findWorkInfo(context: Context, logicalJobId: String): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("$WORK_PREFIX-$logicalJobId")
            .get()
}
