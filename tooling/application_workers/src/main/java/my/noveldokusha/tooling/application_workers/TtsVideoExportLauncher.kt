package my.noveldokusha.tooling.application_workers

import android.content.Context
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TranslationLangPair
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoRequest
import java.security.MessageDigest

/** Builds an immutable request from the current audio TTS profile and video draft. */
object TtsVideoExportLauncher {
    fun enqueue(
        context: Context,
        appPreferences: AppPreferences,
        novelUrl: String,
        novelTitle: String,
        chapterUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        source: TtsAudioSource,
    ): String {
        val videoPrefs = TtsVideoPreferences(context)
        val outputUri = videoPrefs.outputDirectoryUri.ifBlank { appPreferences.TTS_AUDIO_DOWNLOAD_LOCATION_URI.value }
        require(outputUri.isNotBlank()) { "Select a video output folder or an audio output folder first" }
        val pair = if (source == TtsAudioSource.TRANSLATED) appPreferences.translationPairForBook(novelUrl) else TranslationLangPair()
        if (source == TtsAudioSource.TRANSLATED && !pair.source.isNotBlank() || source == TtsAudioSource.TRANSLATED && !pair.target.isNotBlank()) {
            throw IllegalStateException("Translation language pair is incomplete")
        }
        val requestWithoutId = "$novelUrl\u0000$chapterUrl\u0000${source.name}\u0000${pair.source}\u0000${pair.target}\u0000${appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ENGINE.value}\u0000${appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ID.value}\u0000${appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_SPEED.value}\u0000${appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_PITCH.value}"
        val jobId = sha256(requestWithoutId)
        val request = TtsVideoRequest(
            jobId = jobId, novelUrl = novelUrl, novelTitle = novelTitle, chapterUrl = chapterUrl, chapterIndex = chapterIndex, chapterTitle = chapterTitle,
            source = source, translationSourceLang = pair.source, translationTargetLang = pair.target,
            enginePackage = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ENGINE.value, voiceId = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ID.value,
            speed = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_SPEED.value, pitch = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_PITCH.value,
            visual = videoPrefs.visualSettings().copy(slideshowSeed = if (videoPrefs.visualSettings().slideshowSeed != 0L) videoPrefs.visualSettings().slideshowSeed else stableSeed(novelUrl, chapterUrl)),
            outputDirectoryUri = outputUri,
        )
        TtsVideoQueue.enqueue(context, request)
        return jobId
    }
    private fun sha256(s:String):String=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it) }
    private fun stableSeed(a:String,b:String):Long{val d=MessageDigest.getInstance("SHA-256").digest("$a\u0000$b".toByteArray());var v=0L;for(i in 0..7)v=(v shl 8)or(d[i].toLong()and 255);return v}
}
