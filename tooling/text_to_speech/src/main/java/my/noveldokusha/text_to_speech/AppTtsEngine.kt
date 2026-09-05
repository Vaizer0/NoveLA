package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Owns the single live/reader TTS client. Background chapter exports never use this
 * instance; they are created by [TtsAudioEnginePool].
 */
class AppTtsEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    // null means the system default engine until the live client resolves it.
    private var boundEnginePackage: String? = null

    fun getOrCreate(onReady: (() -> Unit)? = null): TextToSpeech {
        if (engine == null) {
            engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    if (boundEnginePackage == null) {
                        boundEnginePackage = engine?.defaultEngine
                    }
                    onReady?.invoke()
                }
            }
        }
        if (boundEnginePackage == null) {
            boundEnginePackage = engine?.defaultEngine
        }
        return engine!!
    }

    fun reinit(enginePackage: String?, onReady: () -> Unit) {
        engine?.stop()
        engine?.shutdown()
        boundEnginePackage = enginePackage?.takeIf { it.isNotEmpty() }
        engine = if (enginePackage.isNullOrEmpty()) {
            TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    boundEnginePackage = engine?.defaultEngine
                    onReady()
                }
            }
        } else {
            TextToSpeech(appContext, { status ->
                if (status == TextToSpeech.SUCCESS) onReady()
            }, enginePackage)
        }
    }

    /** Returns the concrete package currently serving live reader TTS. */
    fun getBoundEnginePackage(): String? =
        boundEnginePackage ?: engine?.defaultEngine

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        boundEnginePackage = null
    }

    companion object {
        @Volatile
        private var instance: AppTtsEngine? = null

        fun getInstance(context: Context): AppTtsEngine {
            return instance ?: synchronized(this) {
                instance ?: AppTtsEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
