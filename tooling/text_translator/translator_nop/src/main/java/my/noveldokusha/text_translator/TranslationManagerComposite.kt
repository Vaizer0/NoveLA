package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.text_translator.domain.TranslationManager
import my.noveldokusha.text_translator.domain.TranslationModelState
import my.noveldokusha.text_translator.domain.TranslatorState

/**
 * Composite translation manager.
 * Active backend is determined by appPreferences.TRANSLATION_PROVIDER:
 *   "GOOGLE_PA"   — translate-pa.googleapis.com (HTML chunks, best quality, default)
 *   "GOOGLE_FREE" — translate.googleapis.com/translate_a/single (plain text)
 *   "GEMINI"      — Google Gemini API (requires user API key; falls back to GOOGLE_PA if key absent)
 */
class TranslationManagerComposite(
    private val coroutineScope: AppCoroutineScope,
    private val geminiManager: TranslationManagerGemini,
    private val googleFreeManager: TranslationManagerGoogleFree,
    private val googlePAManager: TranslationManagerGooglePA,
    private val appPreferences: AppPreferences
) : TranslationManager {

    override val available: Boolean = true
    override val isUsingOnlineTranslation: Boolean = true

    override val models = mutableStateListOf<TranslationModelState>()

    init {
        val allLanguages = mutableSetOf<String>()
        allLanguages.addAll(geminiManager.models.map { it.language })
        allLanguages.addAll(googleFreeManager.models.map { it.language })
        allLanguages.addAll(googlePAManager.models.map { it.language })
        models.addAll(allLanguages.map { lang ->
            TranslationModelState(language = lang, available = true, downloading = false, downloadingFailed = false)
        })
    }

    override suspend fun hasModelDownloaded(language: String): TranslationModelState? =
        models.firstOrNull { it.language == language }

    private fun activeProvider(): String {
        val saved = appPreferences.TRANSLATION_PROVIDER.value
        // If Gemini selected but no key — silently fall back to GOOGLE_PA
        return if (saved == "GEMINI" && appPreferences.TRANSLATION_GEMINI_API_KEY.value.isBlank()) {
            Log.w(TAG, "activeProvider: Gemini selected but no API key — using GOOGLE_PA")
            "GOOGLE_PA"
        } else saved
    }

    fun getActiveTranslatorName(): String = when (activeProvider()) {
        "GEMINI"      -> "Google Gemini API"
        "GOOGLE_FREE" -> "Google Translate (Free)"
        else          -> "Google Translate (Enhanced)"
    }

    override fun getTranslator(source: String, target: String): TranslatorState {
        val provider = activeProvider()
        Log.d(TAG, "getTranslator: source=$source, target=$target, provider=$provider")
        return when {
            provider == "GEMINI"      -> buildGeminiWithFallback(source, target)
            provider == "GOOGLE_FREE" -> googleFreeManager.getTranslator(source, target)
            source == "auto"          -> googleFreeManager.getTranslator(source, target)
            else                      -> googlePAManager.getTranslator(source, target)
        }
    }

    private fun buildGeminiWithFallback(source: String, target: String): TranslatorState {
        val geminiTranslator  = geminiManager.getTranslator(source, target)
        val paTranslator      = googlePAManager.getTranslator(source, target)

        return TranslatorState(
            source = source,
            target = target,
            translate = { input ->
                var lastException: Exception? = null
                repeat(2) { attempt ->
                    try {
                        Log.d(TAG, "Gemini attempt ${attempt + 1}/2")
                        val result = geminiTranslator.translate(input)
                        if (!result.startsWith("[Translation") && !result.startsWith("[API")) {
                            Log.d(TAG, "Gemini translation succeeded")
                            return@TranslatorState result
                        }
                        Log.w(TAG, "Gemini returned error: $result")
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemini attempt ${attempt + 1} failed: ${e.message}", e)
                        lastException = e
                        if (attempt < 1) kotlinx.coroutines.delay(1000L)
                    }
                }
                Log.w(TAG, "Gemini failed, falling back to Google PA")
                try {
                    paTranslator.translate(input)
                } catch (e: Exception) {
                    Log.e(TAG, "Google PA fallback also failed: ${e.message}", e)
                    throw lastException ?: e
                }
            }
        )
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        val resolvedSource = if (sourceLanguage == "auto") {
            val sample = texts.firstOrNull { it.isNotBlank() }?.take(200) ?: ""
            val detected = googleFreeManager.detectLanguage(sample)
            Log.d(TAG, "translateBatch: detected language=$detected")
            detected ?: sourceLanguage
        } else {
            sourceLanguage
        }

        when (activeProvider()) {
            "GEMINI" -> {
                Log.d(TAG, "translateBatch: using Gemini")
                try {
                    return@withContext geminiManager.translateBatch(texts, resolvedSource, targetLanguage)
                } catch (e: Exception) {
                    Log.e(TAG, "translateBatch: Gemini failed, falling back to Google PA", e)
                }
                googlePAManager.translateBatch(texts, resolvedSource, targetLanguage)
            }
            "GOOGLE_FREE" -> {
                Log.d(TAG, "translateBatch: using Google Free")
                googleFreeManager.translateBatch(texts, resolvedSource, targetLanguage)
            }
            else -> {
                Log.d(TAG, "translateBatch: using Google PA")
                googlePAManager.translateBatch(texts, resolvedSource, targetLanguage)
            }
        }
    }

    /**
     * Определение языка всегда делегируется GoogleFree — он всегда доступен
     * и поддерживает sl=auto через стандартный Google Translate endpoint.
     */
    override suspend fun detectLanguage(text: String): String? {
        return googleFreeManager.detectLanguage(text)
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object {
        private const val TAG = "TranslationComposite"
    }
}