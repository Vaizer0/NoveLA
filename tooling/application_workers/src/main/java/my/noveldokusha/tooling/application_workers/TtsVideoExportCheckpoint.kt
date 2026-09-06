package my.noveldokusha.tooling.application_workers

import android.net.Uri
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus

/**
 * Persists the durable VIDEO success checkpoint after the final MP4 and identity manifest have
 * already been validated and published.
 */
suspend fun checkpointSuccess(
    prefs: AppPreferences,
    jobId: String,
    documentUri: Uri,
) {
    TtsAudioQueue.updateState(prefs, jobId) { current ->
        current?.copy(
            status = TtsAudioJobStatus.SUCCESS,
            phase = "VIDEO",
            progress = 100,
            documentUri = documentUri.toString(),
            workRequestId = "",
            videoStagingUri = "",
            videoStagingComplete = false,
            message = "",
        )
    }
}
