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

        val startIndex = keyIndex.getAndIncrement() % keys.size
        var lastException: Exception? = null
        val totalAttempts = retryCount * keys.size

        for (attempt in 0 until totalAttempts) {
            val currentKey = keys[(startIndex + attempt) % keys.size]
            val keyLabel = "key #${(startIndex + attempt) % keys.size + 1}"

            try {
                val response = sendGeminiRequest(
                    systemPrompt = systemPrompt,
                    userText = text,
                    apiKey = currentKey
                )
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

        // All format instructions are in the system prompt.
        // User message contains only the numbered text — clean and simple.
        val userText = texts.mapIndexed { index, text -> "${index + 1}. $text" }
            .joinToString("\n\n")

        var lastException: Exception? = null
        val retryCount = 3
        val totalAttempts = retryCount * availableKeys.size

        repeat(totalAttempts) { attempt ->
            val currentApiKey = availableKeys[attempt % availableKeys.size]
            val attemptWithinKey = attempt / availableKeys.size + 1

            try {
                val response = sendGeminiRequest(
                    systemPrompt = systemPrompt,
                    userText = userText,
                    apiKey = currentApiKey
                )
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

    /**
     * Sends a request to Gemini API using proper systemInstruction + contents split.
     * This gives the model clearer role separation than concatenating everything into one string.
     */
    private fun sendGeminiRequest(
        systemPrompt: String,
        userText: String,
        apiKey: String
    ): okhttp3.Response {
        val jsonBody = JSONObject().apply {
            // systemInstruction is processed separately by Gemini — not mixed with user turn
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", userText) })
                    })
                })
            })
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

    /**
     * Parses a numbered translation response back to a map of original → translated.
     * Uses index-based matching to correctly handle duplicate paragraphs.
     *
     * Tolerates:
     *  - Preamble before the first numbered item (silently discarded)
     *  - Alternate numbering formats: "1)", "**1.**", "№1.", "1 ."
     *  - Missing items (falls back to original text)
     */
    private fun parseNumberedTranslations(translatedText: String, originalTexts: List<String>): Map<String, String> {
        // Index-based map: key = 0-based index, value = translated text
        val byIndex = mutableMapOf<Int, String>()

        // Matches: "1.", "1)", "**1.**", "№1.", "#1.", "1 ." at start of line
        val numberPattern = Regex("""^\*{0,2}[№#]?\s*(\d+)\s*[.)]\*{0,2}\s*""")

        val lines = translatedText.split("\n")
        var currentIndex = -1  // -1 = before first numbered item (preamble)
        var currentText = StringBuilder()

        fun flush() {
            if (currentIndex >= 0 && currentText.isNotBlank()) {
                byIndex[currentIndex] = currentText.toString().trim()
            }
            currentText.clear()
        }

        for (line in lines) {
            val match = numberPattern.find(line)
            if (match != null) {
                flush()
                val num = match.groupValues[1].toIntOrNull() ?: continue
                currentIndex = num - 1  // convert to 0-based
                val rest = line.substring(match.value.length)
                if (rest.isNotBlank()) currentText.append(rest)
            } else {
                if (currentIndex == -1) continue  // preamble before "1." — discard
                val trimmed = line.trim()
                if (currentText.isNotEmpty()) currentText.append("\n")
                currentText.append(trimmed)
            }
        }
        flush()

        // Build final result by index — handles duplicate original texts correctly
        val result = mutableMapOf<String, String>()
        originalTexts.forEachIndexed { index, originalText ->
            val translation = byIndex[index]
            if (translation != null) {
                result[originalText] = translation
            } else {
                Log.w(TAG, "parseNumberedTranslations: missing index $index, using original")
                result[originalText] = originalText
            }
        }

        Log.d(TAG, "parseNumberedTranslations: ${byIndex.size}/${originalTexts.size} parsed")
        return result
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object {
        private const val TAG = "TranslationGemini"
    }
}
