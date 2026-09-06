package my.noveldokusha.core.appPreferences

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
enum class TtsAudioJobStatus { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

@Immutable
@Serializable
data class TtsAudioJobState(
    val chapterUrl: String,
    val novelUrl: String,
    val chapterTitle: String = "",
    val source: TtsAudioSource = TtsAudioSource.ORIGINAL,
    val status: TtsAudioJobStatus,
    val message: String = "",
    /** Current user-facing artifact: WAV during AUDIO, MP4 during VIDEO. */
    val documentUri: String = "",
    val displayName: String = "",
    val progress: Int = 0,
    /** AUDIO = WAV+timeline; VIDEO = cinematic MP4. */
    val phase: String = "AUDIO",
    /** Durable WAV artifact used by video-only generation. */
    val audioUri: String = "",
    /** Durable timing JSON paired with audioUri. */
    val timelineUri: String = "",
    /** Output tree originally selected for this generation. */
    val outputDirectoryUri: String = "",
    /** Current temporary/final MP4 size while VIDEO runs. */
    val videoSizeBytes: Long = 0L,
    /** WorkManager id for the current stage. */
    val workRequestId: String = "",
    /** Durable SAF URI for the current MP4 staging document, before final publication. */
    val videoStagingUri: String = "",
    /** Last WorkManager stop reason observed during VIDEO reconciliation. */
    val videoStopReason: String = "",
    /** Number of app-start/reconciliation VIDEO recoveries attempted for this generation. */
    val videoRecoveryAttempts: Int = 0,
) {
    val isActive: Boolean
        get() = status == TtsAudioJobStatus.QUEUED || status == TtsAudioJobStatus.RUNNING
}
