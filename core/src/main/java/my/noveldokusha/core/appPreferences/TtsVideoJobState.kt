package my.noveldokusha.core.appPreferences

enum class TtsVideoJobStatus { QUEUED, RUNNING, SUCCESS, CANCELLED, FAILED }

data class TtsVideoJobState(
    val chapterUrl: String,
    val novelUrl: String,
    val chapterTitle: String,
    val source: TtsAudioSource,
    val status: TtsVideoJobStatus,
    val workRequestId: String = "",
    val requestJson: String = "",
    val progress: Int = 0,
    val outputUri: String = "",
    val message: String = "",
) { val isActive get() = status == TtsVideoJobStatus.QUEUED || status == TtsVideoJobStatus.RUNNING }
