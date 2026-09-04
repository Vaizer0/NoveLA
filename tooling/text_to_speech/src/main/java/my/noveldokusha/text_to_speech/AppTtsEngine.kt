package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech

class AppTtsEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    // null означает системный движок по умолчанию (service.defaultEngine его всегда и возвращает).
    private var boundEnginePackage: String? = null

    fun getOrCreate(onReady: (() -> Unit)? = null): TextToSpeech {
        if (engine == null) {
            boundEnginePackage = null
            engine = TextToSpeech(appContext) { if (it == TextToSpeech.SUCCESS) onReady?.invoke() }
        }
        return engine!!
    }

    fun reinit(enginePackage: String?, onReady: () -> Unit) {
        engine?.stop()
        engine?.shutdown()
        boundEnginePackage = enginePackage?.takeIf { it.isNotEmpty() }
        engine = if (enginePackage.isNullOrEmpty()) {
            TextToSpeech(appContext) { if (it == TextToSpeech.SUCCESS) onReady() }
        } else {
            TextToSpeech(appContext, { if (it == TextToSpeech.SUCCESS) onReady() }, enginePackage)
        }
    }

    fun getBoundEnginePackage(): String? = boundEnginePackage

    /** Returns whether the reader/live TTS client is currently speaking. */
    fun isSpeaking(): Boolean = engine?.isSpeaking == true

    /**
     * True only when the live reader is speaking through the same engine package
     * requested by an export. Default-engine clients are resolved to the actual
     * system engine package so isolation also works when the preference stores
     * the concrete default package name.
     */
    fun isSpeakingWithEngine(enginePackage: String): Boolean {
        if (!isSpeaking()) return false
        val requested = enginePackage.trim()
        val liveEngine = (boundEnginePackage ?: engine?.defaultEngine.orEmpty()).trim()
        return if (requested.isEmpty()) {
            liveEngine.isEmpty()
        } else {
            liveEngine == requested
        }
    }

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
