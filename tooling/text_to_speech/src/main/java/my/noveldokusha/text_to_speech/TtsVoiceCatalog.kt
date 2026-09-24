package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Loads the multi-engine TTS voice inventory used by Reader settings. */
object TtsVoiceCatalog {
    suspend fun load(context: Context): List<VoiceData> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val seed = initialize(appContext, null) ?: return@withContext emptyList()
        val engines = runCatching { seed.engines.toList() }.getOrDefault(emptyList())
        runCatching { seed.shutdown() }
        val result = linkedMapOf<String, VoiceData>()
        for (engineInfo in engines) {
            val enginePackage = engineInfo.name
            if (enginePackage.isBlank()) continue
            val tts = initialize(appContext, enginePackage) ?: continue
            try {
                tts.voices.orEmpty().forEach { voice ->
                    val data = VoiceData(
                        id = voice.name,
                        language = voice.locale.displayLanguage,
                        needsInternet = voice.isNetworkConnectionRequired,
                        quality = voice.quality,
                        enginePackage = enginePackage,
                    )
                    result[data.key()] = data
                }
            } finally { runCatching { tts.shutdown() } }
        }
        result.values.sortedWith(
            compareBy<VoiceData> { it.language.lowercase() }
                .thenByDescending { it.quality }
                .thenBy { it.id.lowercase() }
                .thenBy { it.enginePackage.lowercase() },
        )
    }
    private fun initialize(context: Context, enginePackage: String?): TextToSpeech? {
        val latch = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = runCatching {
            if (enginePackage.isNullOrBlank()) TextToSpeech(context) { status = it; latch.countDown() }
            else TextToSpeech(context, { status = it; latch.countDown() }, enginePackage)
        }.getOrNull() ?: return null
        return try {
            if (!latch.await(8, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) { runCatching { tts.shutdown() }; null } else tts
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); runCatching { tts.shutdown() }; null
        }
    }
}

fun VoiceData.key(): String = enginePackage + "|" + id
