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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranslationManagerGemini(
    private val coroutineScope: AppCoroutineScope,
    private val appPreferences: AppPreferences
) : TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
        if (keys.isEmpty()) throw IllegalStateException("Gemini: No API keys configured.")

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
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "🚀 Request start: attempt=${attempt + 1}, textLen=${text.length}, key=$keyLabel")

                val response = sendGeminiRequest(systemPrompt, text, currentKey)

                when (response.code) {
                    200 -> {
                        val responseBody = response.body.string()
                        val result = parseGeminiResponse(responseBody)
                        val totalTime = System.currentTimeMillis() - startTime
                        if (result == BLOCKED_MARKER) {
                            Log.w(TAG, "translateWithGemini: content blocked by Gemini filter")
                            throw IOException("Gemini: Content blocked (PROHIBITED_CONTENT). Try a different prompt or model.")
                        }
                        Log.d(TAG, "✅ Success: total=${totalTime}ms, resultLen=${result.length}")
                        if (result.isNotBlank()) return@withContext result
                        Log.w(TAG, "translateWithGemini: empty response after parsing, retrying...")
                        lastException = IOException("Gemini: Empty response after parsing")
                        continue
                    }
                    400 -> {
                        val errorBody = response.body.string()
                        Log.w(TAG, "translateWithGemini: 400 on $keyLabel: $errorBody")
                        lastException = IOException("Gemini: Bad request (400): $errorBody")
                        kotlinx.coroutines.delay(500L * (attempt / keys.size + 1))
                        continue
                    }
                    429 -> {
                        Log.w(TAG, "translateWithGemini: rate limit (429) on $keyLabel")
                        lastException = IOException("Gemini: Rate limit exceeded")
                        continue
                    }
                    in 500..599 -> {
                        val errorBody = response.body.string()
                        Log.w(TAG, "translateWithGemini: server error (${response.code}) on $keyLabel: $errorBody")
                        lastException = IOException("Gemini: Server error (${response.code})")
                        kotlinx.coroutines.delay(500L * (attempt / keys.size + 1))
                    }
                    else -> {
                        val errorBody = response.body.string()
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
        if (availableKeys.isEmpty()) throw IllegalStateException("Gemini: No API keys configured.")

        val useEnglish = appPreferences.TRANSLATION_PROMPT_USE_ENGLISH_LOCALE.value
        val templatePrompt = appPreferences.TRANSLATION_ACTIVE_SYSTEM_PROMPT.value
            .ifBlank { DEFAULT_TRANSLATION_PROMPT }
        val systemPrompt = buildSystemPrompt(templatePrompt, sourceLanguage, targetLanguage, useEnglish)

        val userText = texts.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n\n")

        var lastException: Exception? = null
        val retryCount = 3
        val totalAttempts = retryCount * availableKeys.size

        repeat(totalAttempts) { attempt ->
            val currentApiKey = availableKeys[attempt % availableKeys.size]
            val keyLabel = "key #${attempt % availableKeys.size + 1}"
            val attemptWithinKey = attempt / availableKeys.size + 1

            try {
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "🚀 Batch request: attempt=${attempt + 1}, paragraphs=${texts.size}")

                val response = sendGeminiRequest(systemPrompt, userText, currentApiKey)
                val code = response.code

                when (code) {
                    400 -> {
                        val errorBody = response.body.string()
                        Log.w(TAG, "translateBatch: 400 on $keyLabel: $errorBody")
                        lastException = IOException("Gemini: Bad request (400)")
                        if (attempt < totalAttempts - 1) { kotlinx.coroutines.delay(1000L * attemptWithinKey); return@repeat } else throw lastException!!
                    }
                    429 -> {
                        Log.w(TAG, "translateBatch: rate limit (429) on $keyLabel")
                        lastException = IOException("Gemini: Rate limit exceeded")
                        if (attempt < totalAttempts - 1) { kotlinx.coroutines.delay(500); return@repeat } else throw lastException!!
                    }
                    in 500..599 -> {
                        val errorBody = response.body.string()
                        Log.w(TAG, "translateBatch: server error ($code) on $keyLabel")
                        lastException = IOException("Gemini: Server error ($code)")
                        if (attempt < totalAttempts - 1) { kotlinx.coroutines.delay(2000L * attemptWithinKey); return@repeat } else throw lastException!!
                    }
                    !in 200..299 -> {
                        val errorBody = response.body.string()
                        Log.e(TAG, "translateBatch: API error $code on $keyLabel: $errorBody")
                        throw IOException("Gemini: API error $code")
                    }
                }

                val responseBody = response.body.string()
                val translatedText = parseGeminiResponse(responseBody)
                val totalTime = System.currentTimeMillis() - startTime

                if (translatedText == BLOCKED_MARKER) {
                    Log.w(TAG, "translateBatch: content blocked by Gemini filter")
                    throw IOException("Gemini: Content blocked (PROHIBITED_CONTENT). Try a different prompt or model.")
                }

                Log.d(TAG, "✅ Batch success: total=${totalTime}ms, resultLen=${translatedText.length}")

                if (translatedText.isNotEmpty()) {
                    val translations = parseNumberedTranslations(translatedText, texts)
                    Log.d(TAG, "translateBatch: parsed ${translations.size}/${texts.size} translations")
                    return@withContext translations
                } else {
                    Log.w(TAG, "translateBatch: empty response after parsing, retrying...")
                    lastException = IOException("Gemini: Empty response after parsing")
                    if (attempt < totalAttempts - 1) { kotlinx.coroutines.delay(500L * attemptWithinKey); return@repeat } else throw lastException!!
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch: error on attempt ${attempt + 1} - ${e.message}", e)
                lastException = e
                if (e is IOException && e.message?.contains("Content blocked") == true) throw e
                if (attempt < totalAttempts - 1) kotlinx.coroutines.delay(1000L * attemptWithinKey)
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
        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("topP", 0.95)
        }

        // ✅ safetySettings = BLOCK_NONE
        val safetySettings = JSONArray().apply {
            val categories = listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            )
            for (cat in categories) {
                put(JSONObject().apply {
                    put("category", cat)
                    put("threshold", "BLOCK_NONE")
                })
            }
        }

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
            put("generationConfig", generationConfig)
            put("safetySettings", safetySettings)
            // Явно отключаем googleSearch (по умолчанию включён в Gemini 3.1 Flash Lite)
            put("tools", JSONArray())
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(getApiEndpoint(apiKey))
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        // Логируем сырой ответ для диагностики
        val bodyString = response.body.string()
        Log.d(TAG, "sendGeminiRequest: status=${response.code}, bodyPreview=${bodyString.take(300)}")
        // Восстанавливаем body, т.к. string() его вычитывает
        val newBody = bodyString.toResponseBody("application/json".toMediaType())
        return response.newBuilder().body(newBody).build()
    }

    private fun parseGeminiResponse(responseBody: String): String {
        val trimmed = responseBody.trim()
        Log.d(TAG, "parseGeminiResponse: start length=${responseBody.length}, firstChar='${responseBody.firstOrNull() ?: "EMPTY"}', lastChar='${responseBody.lastOrNull() ?: "EMPTY"}'")

        // 1. Попытка распарсить как массив (редкий формат)
        if (trimmed.startsWith("[")) {
            try {
                Log.d(TAG, "parseGeminiResponse: trying array format")
                val jsonArray = JSONArray(trimmed)
                return buildString {
                    for (i in 0 until jsonArray.length()) {
                        val chunk = jsonArray.getJSONObject(i)
                        val candidates = chunk.getJSONArray("candidates")
                        if (candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            if (candidate.optString("finishReason") == "SAFETY" ||
                                candidate.optString("finishReason") == "PROHIBITED_CONTENT") continue
                            val parts = candidate.getJSONObject("content").getJSONArray("parts")
                            if (parts.length() > 0) append(parts.getJSONObject(0).getString("text"))
                        }
                    }
                }.trim()
            } catch (e: Exception) {
                Log.w(TAG, "parseGeminiResponse: array parse failed", e)
            }
        }

        // 2. Попытка распарсить как объект (стандартный формат Gemini)
        if (trimmed.startsWith("{")) {
            try {
                Log.d(TAG, "parseGeminiResponse: trying object format")
                val jsonResponse = JSONObject(trimmed)

                // Проверка блокировки промпта
                val promptFeedback = jsonResponse.optJSONObject("promptFeedback")
                val blockReason = promptFeedback?.optString("blockReason")
                if (!blockReason.isNullOrEmpty() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                    Log.w(TAG, "Prompt blocked: $blockReason")
                    return BLOCKED_MARKER
                }

                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null) {
                    Log.w(TAG, "parseGeminiResponse: no candidates array in response")
                    return ""
                }
                if (candidates.length() == 0) {
                    Log.w(TAG, "parseGeminiResponse: candidates array is empty")
                    return ""
                }

                val candidate = candidates.getJSONObject(0)
                val finishReason = candidate.optString("finishReason", "UNKNOWN")
                Log.d(TAG, "parseGeminiResponse: finishReason=$finishReason")
                if (finishReason == "SAFETY" || finishReason == "PROHIBITED_CONTENT") {
                    val finishMessage = candidate.optString("finishMessage", "")
                    Log.w(TAG, "Response blocked by content filter: $finishReason — $finishMessage")
                    return BLOCKED_MARKER
                }

                val content = candidate.optJSONObject("content")
                if (content == null) {
                    Log.w(TAG, "parseGeminiResponse: no content in candidate")
                    return ""
                }
                val parts = content.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    Log.w(TAG, "parseGeminiResponse: no parts in content")
                    return ""
                }

                val resultText = parts.getJSONObject(0).getString("text").trim()
                Log.d(TAG, "parseGeminiResponse: parsed from JSON, len=${resultText.length}")
                return resultText

            } catch (e: Exception) {
                Log.w(TAG, "parseGeminiResponse: object parse failed", e)
            }
        }

        // 3. Если ни один парсер не сработал — возвращаем как есть (plain text)
        Log.d(TAG, "parseGeminiResponse: returning raw trimmed, length=${trimmed.length}, preview=${trimmed.take(200)}")
        return trimmed
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
                if (currentText.isNotEmpty()) currentText.append("\n")
                currentText.append(line.trim())
            }
        }
        flush()

        return originalTexts.mapIndexedNotNull { index, originalText ->
            byIndex[index]?.let { originalText to it }
        }.toMap().also {
            Log.d(TAG, "parseNumberedTranslations: ${it.size}/${originalTexts.size} parsed")
        }
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object {
        private const val TAG = "TranslationGemini"
        private const val BLOCKED_MARKER = "__GEMINI_BLOCKED__"
    }
}