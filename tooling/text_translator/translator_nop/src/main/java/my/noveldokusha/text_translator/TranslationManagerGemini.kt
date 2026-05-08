package my.noveldokusha.text_translator

import my.noveldokusha.text_translator.buildSystemPrompt
import my.noveldokusha.text_translator.DEFAULT_TRANSLATION_PROMPT

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
import java.io.IOException
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
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val keyIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private val apiKeys: List<String>
        get() = appPreferences.TRANSLATION_GEMINI_API_KEY.value
            .split("\n", ";", ",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun getApiEndpoint(key: String): String {
        val model = appPreferences.TRANSLATION_GEMINI_MODEL.value.ifBlank { "gemini-3.1-flash-lite" }
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
                        // Пустой ответ — retry, не падаем молча
                        Log.w(TAG, "translateWithGemini: empty response on attempt $attempt, retrying...")
                        lastException = IOException("Gemini: Empty response")
                        continue
                    }
                    400 -> {
                        val errorBody = response.body?.string() ?: ""
                        Log.w(TAG, "translateWithGemini: 400 on $keyLabel (attempt $attempt): $errorBody")
                        lastException = IOException("Gemini: Bad request (400): $errorBody")
                        kotlinx.coroutines.delay(500L * (attempt / keys.size + 1))
                        continue
                    }
                    429 -> {
                        Log.w(TAG, "translateWithGemini: rate limit (429) on $keyLabel, rotating...")
                        lastException = IOException("Gemini: Rate limit exceeded ($keyLabel)")
                        continue
                    }
                    in 500..599 -> {
                        val errorBody = response.body?.string() ?: ""
                        Log.w(TAG, "translateWithGemini: server error (${response.code}) on $keyLabel: $errorBody")
                        lastException = IOException("Gemini: Server error (${response.code})")
                        kotlinx.coroutines.delay(500L * (attempt / keys.size + 1))
                    }
                    else -> {
                        val errorBody = response.body?.string() ?: ""
                        Log.e(TAG, "translateWithGemini: API error ${response.code} on $keyLabel: $errorBody")
                        throw IOException("Gemini: API error ${response.code}: $errorBody")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (e is IOException && attempt < totalAttempts - 1) continue
                throw e
            }
        }
        throw lastException ?: IOException("Gemini: All attempts failed")
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
            throw IllegalStateException("Gemini: No API keys configured.")
        }

        val useEnglish = appPreferences.TRANSLATION_PROMPT_USE_ENGLISH_LOCALE.value
        val templatePrompt = appPreferences.TRANSLATION_ACTIVE_SYSTEM_PROMPT.value
            .ifBlank { DEFAULT_TRANSLATION_PROMPT }
        val systemPrompt = buildSystemPrompt(templatePrompt, sourceLanguage, targetLanguage, useEnglish)

        val userText = texts.mapIndexed { index, text -> "${index + 1}. $text" }
            .joinToString("\n\n")

        var lastException: Exception? = null
        val retryCount = 3
        val totalAttempts = retryCount * availableKeys.size

        repeat(totalAttempts) { attempt ->
            val currentApiKey = availableKeys[attempt % availableKeys.size]
            val keyLabel = "key #${attempt % availableKeys.size + 1}"
            val attemptWithinKey = attempt / availableKeys.size + 1

            try {
                val response = sendGeminiRequest(
                    systemPrompt = systemPrompt,
                    userText = userText,
                    apiKey = currentApiKey
                )
                val code = response.code

                when (code) {
                    400 -> {
                        val errorBody = response.body?.string() ?: ""
                        Log.w(TAG, "translateBatch: 400 on $keyLabel (attempt $attempt): $errorBody")
                        lastException = IOException("Gemini: Bad request (400): $errorBody")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(1000L * attemptWithinKey)
                            return@repeat
                        } else {
                            throw lastException!!
                        }
                    }
                    429 -> {
                        Log.w(TAG, "translateBatch: rate limit (429) on $keyLabel, rotating")
                        lastException = IOException("Gemini: Rate limit exceeded ($keyLabel)")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(500)
                            return@repeat
                        } else {
                            throw lastException!!
                        }
                    }
                    in 500..599 -> {
                        val errorBody = response.body?.string() ?: ""
                        val waitTime = 2000L * attemptWithinKey
                        Log.w(TAG, "translateBatch: server error ($code) on $keyLabel, waiting ${waitTime}ms: $errorBody")
                        lastException = IOException("Gemini: Server error ($code)")
                        if (attempt < totalAttempts - 1) {
                            kotlinx.coroutines.delay(waitTime)
                            return@repeat
                        } else {
                            throw lastException!!
                        }
                    }
                    !in 200..299 -> {
                        val errorBody = response.body?.string() ?: ""
                        Log.e(TAG, "translateBatch: API error $code on $keyLabel: $errorBody")
                        throw IOException("Gemini: API error $code: $errorBody")
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
                    // Пустой ответ — retry
                    Log.w(TAG, "translateBatch: empty response on attempt $attempt, retrying...")
                    lastException = IOException("Gemini: Empty response")
                    if (attempt < totalAttempts - 1) {
                        kotlinx.coroutines.delay(500L * attemptWithinKey)
                        return@repeat
                    } else {
                        throw lastException!!
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch: error on attempt ${attempt + 1} - ${e.message}", e)
                lastException = e
                if (attempt < totalAttempts - 1) {
                    kotlinx.coroutines.delay(1000L * attemptWithinKey)
                }
            }
        }

        throw lastException ?: IOException("Gemini: Batch translation failed after $retryCount attempts")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun sendGeminiRequest(
        systemPrompt: String,
        userText: String,
        apiKey: String
    ): okhttp3.Response {
        val jsonBody = JSONObject().apply {
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
                        val candidate = candidates.getJSONObject(0)
                        val finishReason = candidate.optString("finishReason")
                        if (finishReason == "SAFETY") {
                            Log.w(TAG, "parseGeminiResponse: chunk blocked by safety filters")
                            continue
                        }
                        val parts = candidate.getJSONObject("content").getJSONArray("parts")
                        if (parts.length() > 0) append(parts.getJSONObject(0).getString("text"))
                    }
                }
            }.trim()
        } else {
            val jsonResponse = JSONObject(responseBody)

            // Проверяем блокировку на уровне запроса
            val promptFeedback = jsonResponse.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason")
            if (!blockReason.isNullOrEmpty() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                Log.w(TAG, "parseGeminiResponse: prompt blocked: $blockReason")
                return ""
            }

            val candidates = jsonResponse.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""

            val candidate = candidates.getJSONObject(0)
            val finishReason = candidate.optString("finishReason")
            if (finishReason == "SAFETY") {
                Log.w(TAG, "parseGeminiResponse: response blocked by safety filters (finishReason=SAFETY)")
                return ""
            }

            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            if (parts.length() > 0) parts.getJSONObject(0).getString("text").trim() else ""
        }
    }

    private fun parseNumberedTranslations(translatedText: String, originalTexts: List<String>): Map<String, String> {
        val byIndex = mutableMapOf<Int, String>()
        val numberPattern = Regex("""^\*{0,2}[№#]?\s*(\d+)\s*[.)]\*{0,2}\s*""")

        val lines = translatedText.split("\n")
        var currentIndex = -1
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
                currentIndex = num - 1
                val rest = line.substring(match.value.length)
                if (rest.isNotBlank()) currentText.append(rest)
            } else {
                if (currentIndex == -1) continue
                val trimmed = line.trim()
                if (currentText.isNotEmpty()) currentText.append("\n")
                currentText.append(trimmed)
            }
        }
        flush()

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