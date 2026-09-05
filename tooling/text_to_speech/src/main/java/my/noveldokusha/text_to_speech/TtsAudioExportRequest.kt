package my.noveldokusha.text_to_speech

import my.noveldokusha.core.appPreferences.TtsAudioSource

/** Output pipeline requested for a chapter export. */
enum class TtsExportMode {
    AUDIO,
    CINEMATIC_VIDEO,
}

/**
 * Immutable snapshot of one chapter export request.
 *
 * The same request parameters are used by both audio and cinematic-video exports;
 * video generation reuses the exact WAV + timeline pair before invoking the native renderer.
 */
data class TtsAudioExportRequest(
    /** Unique logical id for duplicate protection. */
    val jobId: String,
    val novelTitle: String,
    val novelUrl: String,
    val chapterUrl: String,
    val chapterTitle: String,
    /** Position of the chapter in the book list. */
    val chapterIndex: Int,
    val source: TtsAudioSource,
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
    /** SAF tree URI of the destination folder. */
    val outputDirectoryUri: String,
    /** WAV for the current exporter implementation. */
    val format: String = TtsAudioFormat.WAV,
    /** Snapshot of the translation language pair when this request was created. */
    val translationSourceLang: String = "",
    val translationTargetLang: String = "",
    /** Final artifact requested by the caller. */
    val exportMode: TtsExportMode = TtsExportMode.AUDIO,
) {
    companion object {
        /**
         * Deterministic id for one logical artifact. Mode is included so audio and
         * cinematic video can coexist for the same chapter/source/language pair.
         */
        fun makeJobId(
            novelUrl: String,
            chapterUrl: String,
            source: TtsAudioSource,
            translationSourceLang: String = "",
            translationTargetLang: String = "",
            exportMode: TtsExportMode = TtsExportMode.AUDIO,
        ): String {
            val raw = "$novelUrl::$chapterUrl::${source.name}" +
                "::${translationSourceLang}::${translationTargetLang}::${exportMode.name}"
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
            return "tts_${exportMode.name.lowercase()}_$sha"
        }
    }
}

object TtsAudioFormat {
    const val WAV = "wav"
    const val M4A = "m4a"
}
