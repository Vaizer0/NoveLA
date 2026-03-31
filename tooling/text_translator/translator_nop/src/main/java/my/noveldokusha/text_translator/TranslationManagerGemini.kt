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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Translation manager using Google Gemini API
 * Requires API key configuration
 * FOSS version - API-only, no MLKit
 * Note: No in-memory cache — DB (ChapterTranslationDao) is the single source of truth.
 */
class TranslationManagerGemini(
    private val coroutineScope: AppCoroutineScope,
    private val appPreferences: AppPreferences
) : TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKeys: List<String>
        get() = appPreferences.TRANSLATION_GEMINI_API_KEY.value
            .split("\n", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun getApiEndpoint(key: String): String {
        val model = appPreferences.TRANSLATION_GEMINI_MODEL.value.ifBlank { "gemini-2.5-flash-lite" }
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
    }

    override val available = true
    override val isUsingOnlineTranslation: Boolean
        get() = apiKeys.isNotEmpty()

    override val models = mutableStateListOf<TranslationModelState>().apply {
        val supportedLanguages = listOf(
            "en", "zh", "ja", "ko", "es", "fr", "de", "it", "pt", "ru",
            "ar", "hi", "th", "vi", "id", "tr", "pl", "nl", "sv", "da",
            "fi", "no", "cs", "el", "he", "ro", "hu", "uk", "bg", "hr"
        )
        addAll(supportedLanguages.map { lang ->
            TranslationModelState(
                language = lang,
                available = true,
                downloading = false,
                downloadingFailed = false
            )
        })
    }

    override suspend fun hasModelDownloaded(language: String): TranslationModelState? {
        return models.firstOrNull { it.language == language }
    }

    override fun getTranslator(source: String, target: String): TranslatorState {
        Log.d(TAG, "getTranslator: source=$source, target=$target, apiKeysConfigured=${apiKeys.size}")
        return TranslatorState(
            source = source,
            target = target,
            translate = { input -> translateWithGemini(input, source, target) }
        )
    }

    private suspend fun translateWithGemini(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        retryCount: Int = 3
    ): String = withContext(Dispatchers.IO) {
        val availableKeys = apiKeys

        if (availableKeys.isEmpty()) {
            Log.e(TAG, "translateWithGemini: No API keys configured!")
            return@withContext "[Translation unavailable: Gemini API key not configured. Please add your API key in Settings → Gemini Translation]"
        }

        val sourceLangName = Locale(sourceLanguage).displayLanguage
        val targetLangName = Locale(targetLanguage).displayLanguage

        val prompt = buildTranslationPrompt(text, sourceLangName, targetLangName)

        var lastException: Exception? = null
        val totalAttempts = retryCount * availableKeys.size

        repeat(totalAttempts) { attempt ->
            val currentApiKey = availableKeys[attempt % availableKeys.size]
            val attemptWithinKey = attempt / availableKeys.size + 1

            try {
                val response = sendGeminiRequest(prompt, currentApiKey)
                val code = response.code

                when (code) {
                    429 -> {
                        Log.w(TAG, "translateWithGemini: Rate limit (429) on key ${(attempt % availableKeys.size) + 1}, rotating")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(500)
                            return@repeat
                        } else {
                            return@withContext "[Translation rate limit exceeded on all API keys. Please wait and try again.]"
                        }
                    }
                    in 500..599 -> {
                        val waitTime = 2000L * attemptWithinKey
                        Log.w(TAG, "translateWithGemini: Server error ($code), waiting ${waitTime}ms")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(waitTime)
                            return@repeat
                        } else {
                            return@withContext "[Translation service temporarily unavailable ($code)]"
                        }
                    }
                    !in 200..299 -> {
                        Log.e(TAG, "translateWithGemini: API error $code")
                        return@withContext "[Translation failed: $code]"
                    }
                }

                val responseBody = response.body?.string() ?: ""
                val translatedText = parseGeminiResponse(responseBody)

                if (translatedText.isNotEmpty()) {
                    Log.d(TAG, "translateWithGemini: success, result length=${translatedText.length}")
                    return@withContext translatedText
                } else {
                    Log.e(TAG, "translateWithGemini: empty response")
                    return@withContext "[Translation failed: invalid response]"
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateWithGemini: error on attempt ${attempt + 1} - ${e.message}", e)
                lastException = e
                if (attempt < totalAttempts - 1) {
                    kotlinx.coroutines.delay(1000L * attemptWithinKey)
                } else {
                    return@withContext "[Translation error: ${e.message?.take(50) ?: "unknown"}]"
                }
            }
        }

        return@withContext "[Translation failed after $retryCount attempts]"
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        Log.d(TAG, "translateBatch: translating ${texts.size} paragraphs")

        val availableKeys = apiKeys
        if (availableKeys.isEmpty()) {
            Log.e(TAG, "translateBatch: No API keys configured!")
            return@withContext texts.associateWith { "[API key not configured]" }
        }

        val sourceLangName = Locale(sourceLanguage).displayLanguage
        val targetLangName = Locale(targetLanguage).displayLanguage

        val numberedTexts = texts.mapIndexed { index, text -> "${index + 1}. $text" }
            .joinToString("\n\n")

        val prompt = buildBatchTranslationPrompt(numberedTexts, sourceLangName, targetLangName)

        var lastException: Exception? = null
        val retryCount = 3
        val totalAttempts = retryCount * availableKeys.size

        repeat(totalAttempts) { attempt ->
            val currentApiKey = availableKeys[attempt % availableKeys.size]
            val attemptWithinKey = attempt / availableKeys.size + 1

            try {
                val response = sendGeminiRequest(prompt, currentApiKey)
                val code = response.code

                when (code) {
                    429 -> {
                        Log.w(TAG, "translateBatch: Rate limit (429) on key ${(attempt % availableKeys.size) + 1}, rotating")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(500)
                            return@repeat
                        } else {
                            return@withContext texts.associateWith { "[Rate limit exceeded on all API keys]" }
                        }
                    }
                    in 500..599 -> {
                        val waitTime = 2000L * attemptWithinKey
                        Log.w(TAG, "translateBatch: Server error ($code), waiting ${waitTime}ms")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(waitTime)
                            return@repeat
                        } else {
                            return@withContext texts.associateWith { "[Service unavailable]" }
                        }
                    }
                    !in 200..299 -> {
                        Log.e(TAG, "translateBatch: API error $code")
                        return@withContext texts.associateWith { "[Translation failed: $code]" }
                    }
                }

                val responseBody = response.body?.string() ?: ""
                val translatedText = parseGeminiResponse(responseBody)

                if (translatedText.isNotEmpty()) {
                    Log.d(TAG, "translateBatch: success, parsing ${texts.size} translations")
                    val translations = parseNumberedTranslations(translatedText, texts)
                    Log.d(TAG, "translateBatch: parsed ${translations.size}/${texts.size} translations")
                    return@withContext translations
                } else {
                    Log.e(TAG, "translateBatch: empty response")
                    return@withContext texts.associateWith { "[Invalid response]" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch: error on attempt ${attempt + 1} - ${e.message}", e)
                lastException = e
                if (attempt < totalAttempts - 1) {
                    kotlinx.coroutines.delay(1000L * attemptWithinKey)
                }
            }
        }

        Log.e(TAG, "translateBatch: failed after $retryCount attempts")
        return@withContext texts.associateWith { "[Translation failed]" }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun sendGeminiRequest(prompt: String, apiKey: String): okhttp3.Response {
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 0)
                })
            })
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(getApiEndpoint(apiKey))
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()
        return client.newCall(request).execute()
    }

    private fun parseGeminiResponse(responseBody: String): String {
        return if (responseBody.trimStart().startsWith("[")) {
            val jsonArray = JSONArray(responseBody)
            buildString {
                for (i in 0 until jsonArray.length()) {
                    val chunk = jsonArray.getJSONObject(i)
                    val candidates = chunk.getJSONArray("candidates")
                    if (candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                        if (parts.length() > 0) append(parts.getJSONObject(0).getString("text"))
                    }
                }
            }.trim()
        } else {
            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val parts = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                if (parts.length() > 0) parts.getJSONObject(0).getString("text").trim() else ""
            } else ""
        }
    }

    private fun parseNumberedTranslations(translatedText: String, originalTexts: List<String>): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        val lines = translatedText.split("\n").filter { it.isNotBlank() }
        var currentIndex = 0
        var currentTranslation = StringBuilder()

        for (line in lines) {
            val numberMatch = Regex("^(\\d+)\\.\\s*").find(line)
            if (numberMatch != null) {
                if (currentTranslation.isNotEmpty() && currentIndex > 0) {
                    originalTexts.getOrNull(currentIndex - 1)?.let {
                        translations[it] = currentTranslation.toString().trim()
                    }
                    currentTranslation.clear()
                }
                currentIndex = numberMatch.groupValues[1].toIntOrNull() ?: (currentIndex + 1)
                currentTranslation.append(line.substring(numberMatch.range.last + 1))
            } else {
                if (currentTranslation.isNotEmpty()) currentTranslation.append(" ")
                currentTranslation.append(line.trim())
            }
        }
        if (currentTranslation.isNotEmpty() && currentIndex > 0) {
            originalTexts.getOrNull(currentIndex - 1)?.let {
                translations[it] = currentTranslation.toString().trim()
            }
        }

        // Заполняем пропуски оригиналом (не теряем параграфы)
        originalTexts.forEach { text ->
            if (!translations.containsKey(text)) {
                Log.w(TAG, "parseNumberedTranslations: missing translation, using original")
                translations[text] = text
            }
        }

        return translations
    }

    private fun buildTranslationPrompt(text: String, sourceLangName: String, targetLangName: String): String {
        val isEnglish = targetLangName.equals("English", ignoreCase = true)
        val terminologyRule = if (isEnglish) {
            "2. TRANSLATE all terminology to English equivalents:\n   - Cultivation realms, technique names, sect names, artifact names"
        } else {
            "2. TRANSLATE all terminology to $targetLangName:\n   - Cultivation realms, technique names, sect names, artifact names\n   - Use natural $targetLangName translations for these terms"
        }
        
        return """
            You are an expert Chinese webnovel translator specializing in cultivation/xianxia novels. Translate the following text from $sourceLangName to $targetLangName.
            
            CRITICAL TRANSLATION RULES:
            1. PRESERVE character names in pinyin (e.g., Chen Fei, Lin Xi, Zhang Wei, Wang Hao)
            $terminologyRule
            3. Produce natural, fluent $targetLangName. Remove ads or author notes.
            4. Provide ONLY the translation, no explanations.
            
            Text to translate:
            $text
        """.trimIndent()
    }

    private fun buildBatchTranslationPrompt(numberedTexts: String, sourceLangName: String, targetLangName: String): String {
        val isEnglish = targetLangName.equals("English", ignoreCase = true)
        val terminologyRule = if (isEnglish) {
            "2. TRANSLATE all terminology to English equivalents:\n   - Cultivation realms, technique names, sect names, artifact names"
        } else {
            "2. TRANSLATE all terminology to $targetLangName:\n   - Cultivation realms, technique names, sect names, artifact names\n   - Use natural $targetLangName translations for these terms"
        }
        
        return """
            You are an expert Chinese webnovel translator specializing in cultivation/xianxia novels. Translate these numbered paragraphs from $sourceLangName to $targetLangName.
            
            CRITICAL TRANSLATION RULES:
            1. PRESERVE character names in pinyin (e.g., Chen Fei, Lin Xi, Zhang Wei, Wang Hao)
            $terminologyRule
            3. Produce natural, fluent $targetLangName. Remove ads or author notes.
            4. Maintain exact numbering format (1., 2., 3., etc.). Provide ONLY translations.
            
            Paragraphs to translate:
            $numberedTexts
        """.trimIndent()
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object {
        private const val TAG = "TranslationGemini"
    }
}