package my.noveldokusha.core.appPreferences

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Status of a chapter audio/video generation job. */
@Immutable
@Serializable
enum class TtsAudioJobStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

@Immutable
@Serializable
data class TtsAudioJobState(
    val chapterUrl: String,
    val novelUrl: String,
    val chapterTitle: String = "",
    val source: TtsAudioSource = TtsAudioSource.ORIGINAL,
    val status: TtsAudioJobStatus,
    val message: String = "",
    /** Current user-visible artifact: WAV during AUDIO, MP4 during VIDEO. */
    val documentUri: String = "",
    val displayName: String = "",
    val progress: Int = 0,
    /** AUDIO = WAV+timeline generation; VIDEO = cinematic MP4 generation. */
    val phase: String = "AUDIO",
    /** Persisted WAV document used by the video-only stage. */
    val audioUri: String = "",
    /** Persisted timeline JSON paired with [audioUri]. */
    val timelineUri: String = "",
    /** Current/generated MP4 size while VIDEO is running. */
    val videoSizeBytes: Long = 0L,
    /** WorkManager id for the currently executing stage. */
    val workRequestId: String = "",
) {
    val isActive: Boolean
        get() = status == TtsAudioJobStatus.QUEUED || status == TtsAudioJobStatus.RUNNING
}
