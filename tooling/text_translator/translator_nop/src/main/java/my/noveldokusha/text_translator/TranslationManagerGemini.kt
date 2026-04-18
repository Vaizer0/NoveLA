package my.noveldokusha.text_translator

import my.noveldokusha.text_translator.buildSystemPrompt
import my.noveldokusha.text_translator.DEFAULT_TRANSLATION_PROMPT

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
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

    private val keyIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private val apiKeys: List<String>
        get() = appPreferences.TRANSLATION_GEMINI_API_KEY.value
            .split("\n", ";", ",")
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
        val keys = apiKeys
        if (keys.isEmpty()) {
            throw IllegalStateException("Gemini: No API keys configured.")
        }

        val useEnglish = appPreferences.TRANSLATION_PROMPT_USE_ENGLISH_LOCALE.value
        val templatePrompt = appPreferences.TRANSLATION_ACTIVE_SYSTEM_PROMPT.value
            .ifBlank { DEFAULT_TRANSLATION_PROMPT }
        val systemPrompt = buildSystemPrompt(templatePrompt, sourceLanguage, targetLanguage, useEnglish)
        val prompt = buildGeminiUserPrompt(text, systemPrompt)

        val startIndex = keyIndex.getAndIncrement() % keys.size
        var lastException: Exception? = null
        val totalAttempts = retryCount * keys.size

        for (attempt in 0 until totalAttempts) {
            val currentKey = keys[(startIndex + attempt) % keys.size]
            val keyLabel = "key #${(startIndex + attempt) % keys.size + 1}"

            try {
                val response = sendGeminiRequest(prompt, currentKey)
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: ""
                        val result = parseGeminiResponse(body)
                        if (result.isNotBlank()) return@withContext result
                    }
                    429 -> {
                        Log.w(TAG, "Rate limit (429) on $keyLabel, rotating...")
                        lastException = Exception("Gemini: Rate limit exceeded ($keyLabel)")
                        continue
                    }
                    in 500..599 -> {
                        Log.w(TAG, "Server error (${response.code}) on $keyLabel")
                        lastException =
                            kotlinx.io.IOException("Gemini: Server error (${response.code})")
                        // При 5xx можно подождать чуть-чуть перед следующим ключом
                        kotlinx.coroutines.delay(500L * (attempt / keys.size + 1))
                    }
                    else -> {
                        val errorMsg = "API error ${response.code}"
                        Log.e(TAG, errorMsg)
                        throw kotlinx.io.IOException("Gemini: $errorMsg")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (e is IOException && attempt < totalAttempts - 1) continue
            }
        }
        throw lastException ?: Exception("Gemini: All attempts failed")
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

        val useEnglish = appPreferences.TRANSLATION_PROMPT_USE_ENGLISH_LOCALE.value
        val templatePrompt = appPreferences.TRANSLATION_ACTIVE_SYSTEM_PROMPT.value
            .ifBlank { DEFAULT_TRANSLATION_PROMPT }
        val systemPrompt = buildSystemPrompt(templatePrompt, sourceLanguage, targetLanguage, useEnglish)

        val numberedTexts = texts.mapIndexed { index, text -> "${index + 1}. $text" }
            .joinToString("\n\n")

        val prompt = buildGeminiBatchUserPrompt(numberedTexts, systemPrompt)

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

        throw lastException ?: Exception("Gemini: Batch translation failed after $retryCount attempts")
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
            // Убрали thinkingConfig для совместимости со всеми моделями
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(getApiEndpoint(apiKey))
            .addHeader("Content-Type", "application/json")
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

    /**
     * Формирует итоговый промпт для одиночного перевода.
     * Системный промпт уже содержит инструкции и названия языков —
     * пользовательская часть содержит только исходный текст.
     */
    private fun buildGeminiUserPrompt(text: String, systemPrompt: String): String =
        "$systemPrompt\n\nText to translate:\n$text"

    /**
     * Формирует итоговый промпт для батч-перевода с нумерацией.
     */
    private fun buildGeminiBatchUserPrompt(numberedTexts: String, systemPrompt: String): String =
        "$systemPrompt\n\nTranslate the following numbered paragraphs. Keep the numbering format exactly (1., 2., etc.). Return ONLY the numbered translations:\n\n$numberedTexts"

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object {
        private const val TAG = "TranslationGemini"
    }
}