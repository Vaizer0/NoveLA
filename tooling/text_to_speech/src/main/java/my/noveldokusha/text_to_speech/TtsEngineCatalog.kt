package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Discovers installed TTS engines and their voices without sharing a live/reader client.
 *
 * This catalog is independent from [AppTtsEngine]. It lets settings and export admission
 * distinguish separate TextToSpeech clients from genuinely separate engine services.
 */
object TtsEngineCatalog {
    data class Engine(
        val packageName: String,
        val label: String,
        val voices: List<Voice>,
    )

    data class Voice(
        val enginePackage: String,
        val id: String,
        val locale: Locale,
        val needsInternet: Boolean,
        val quality: Int,
    )

    /** Discover all installed engines and all voices exposed by each engine. */
    suspend fun discover(context: Context): List<Engine> = withContext(Dispatchers.Default) {
        val probe = createTts(context.applicationContext, "")
        val engineInfos = try {
            probe.engines?.toList().orEmpty()
        } finally {
            runCatching { probe.shutdown() }
        }

        buildList {
            for (engineInfo in engineInfos) {
                val tts = runCatching {
                    createTts(context.applicationContext, engineInfo.name)
                }.getOrNull() ?: continue
                try {
                    val voices = tts.voices.orEmpty()
                        .map { voice ->
                            Voice(
                                enginePackage = engineInfo.name,
                                id = voice.name,
                                locale = voice.locale,
                                needsInternet = voice.isNetworkConnectionRequired,
                                quality = voice.quality,
                            )
                        }
                        .distinctBy { it.id }
                        .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.id }))
                    add(
                        Engine(
                            packageName = engineInfo.name,
                            label = engineInfo.label?.toString().orEmpty().ifBlank { engineInfo.name },
                            voices = voices,
                        )
                    )
                } finally {
                    runCatching { tts.shutdown() }
                }
            }
        }
    }

    /**
     * Resolve an export engine so live reader TTS and background export do not use the
     * same engine service when a different installed engine can provide a compatible voice.
     *
     * The configured engine/voice are preserved whenever possible. If the configured engine
     * is the live engine, an alternate installed engine is preferred when it exposes either
     * the exact voice or a voice with the same locale. With only one installed engine, the
     * requested engine is retained and the live-reader fairness fallback remains necessary.
     */
    suspend fun resolveForExport(
        context: Context,
        requestedEnginePackage: String,
        requestedVoiceId: String,
        liveEnginePackage: String,
    ): ResolvedVoice = withContext(Dispatchers.Default) {
        val engines = discover(context)
        if (engines.isEmpty()) {
            throw TtsExportException("No TTS engine is installed")
        }

        val requestedEngine = requestedEnginePackage.trim()
        val liveEngine = liveEnginePackage.trim()
        val direct = findVoice(engines, requestedEngine, requestedVoiceId)
            ?: findVoiceById(engines, requestedVoiceId)

        if (requestedEngine.isNotEmpty() && requestedEngine != liveEngine) {
            return@withContext direct?.takeIf { it.enginePackage == requestedEngine }
                ?: throw TtsExportException(
                    "Voice '$requestedVoiceId' not found in engine '$requestedEngine'"
                )
        }

        val requestedVoice = direct
            ?: throw TtsExportException(
                "Voice '$requestedVoiceId' is not available in the installed TTS engines"
            )

        val alternateEngines = engines.filter { it.packageName != liveEngine }
        if (liveEngine.isNotEmpty() && alternateEngines.isNotEmpty()) {
            val exactMatch = alternateEngines
                .asSequence()
                .flatMap { it.voices.asSequence() }
                .firstOrNull { it.id == requestedVoice.id }
            if (exactMatch != null) {
                return@withContext ResolvedVoice(
                    enginePackage = exactMatch.enginePackage,
                    voiceId = exactMatch.id,
                    changedEngine = true,
                    changedVoice = false,
                )
            }

            val localeMatch = alternateEngines
                .asSequence()
                .flatMap { it.voices.asSequence() }
                .filter { it.locale == requestedVoice.locale }
                .sortedWith(
                    compareByDescending<Voice> { !it.needsInternet }
                        .thenByDescending { it.quality }
                        .thenBy { it.id },
                )
                .firstOrNull()
            if (localeMatch != null) {
                return@withContext ResolvedVoice(
                    enginePackage = localeMatch.enginePackage,
                    voiceId = localeMatch.id,
                    changedEngine = true,
                    changedVoice = true,
                )
            }
        }

        ResolvedVoice(
            enginePackage = requestedVoice.enginePackage,
            voiceId = requestedVoice.id,
            changedEngine = false,
            changedVoice = false,
        )
    }

    data class ResolvedVoice(
        val enginePackage: String,
        val voiceId: String,
        val changedEngine: Boolean,
        val changedVoice: Boolean,
    )

    private fun findVoice(
        engines: List<Engine>,
        enginePackage: String,
        voiceId: String,
    ): Voice? {
        if (enginePackage.isBlank()) return null
        return engines
            .firstOrNull { it.packageName == enginePackage }
            ?.voices
            ?.firstOrNull { it.id == voiceId }
    }

    private fun findVoiceById(engines: List<Engine>, voiceId: String): Voice? =
        engines.asSequence()
            .flatMap { it.voices.asSequence() }
            .firstOrNull { it.id == voiceId }

    private suspend fun createTts(context: Context, enginePackage: String): TextToSpeech =
        suspendCancellableCoroutine { continuation ->
            lateinit var created: TextToSpeech
            created = TextToSpeech(
                context,
                { status ->
                    if (continuation.isActive) {
                        if (status == TextToSpeech.SUCCESS) {
                            continuation.resume(created)
                        } else {
                            continuation.resumeWithException(
                                TtsExportException(
                                    "TTS engine '${enginePackage.ifBlank { "default" }}' init failed: status=$status"
                                )
                            )
                        }
                    }
                },
                enginePackage.ifBlank { null },
            )
            continuation.invokeOnCancellation {
                runCatching { created.shutdown() }
            }
        }
}
