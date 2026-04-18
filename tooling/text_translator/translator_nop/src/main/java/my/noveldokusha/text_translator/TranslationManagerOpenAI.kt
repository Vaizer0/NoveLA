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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Translation manager using any OpenAI-compatible API.
 * Supports custom base URL (OpenAI, OpenRouter, Mistral, DeepSeek, local Ollama, etc.)
 *
 * Key rotation strategy:
 *   - Keys are split by newline/semicolon/comma from TRANSLATION_OPENAI_API_KEYS
 *   - Round-robin via atomic counter — each request picks the next key in sequence
 *   - On 401: tries all remaining keys before throwing
 *   - On 429: tries next key, if all exhausted throws with clear message
 *   - On 5xx: throws with HTTP code in message
 *   - On timeout/network error: rethrows IOException as-is
 *
 * No silent fallback — all errors are thrown so the caller and UI can report them.
 */
class TranslationManagerOpenAI(
    private val coroutineScope: AppCoroutineScope,
    private val appPreferences: AppPreferences
) : TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Round-robin counter shared across all calls
    private val keyIndex = AtomicInteger(0)

    private val apiKeys: List<String>
        get() = appPreferences.TRANSLATION_OPENAI_API_KEYS.value
            .split("\n", ";", ",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private val baseUrl: String
        get() = appPreferences.TRANSLATION_OPENAI_BASE_URL.value
            .trimEnd('/')
            .ifBlank { "https://api.openai.com" }

    private val model: String
        get() = appPreferences.TRANSLATION_OPENAI_MODEL.value
            .ifBlank { "gpt-4o-mini" }

    private val systemPromptTemplate: String
        get() = appPreferences.TRANSLATION_ACTIVE_SYSTEM_PROMPT.value
            .ifBlank { DEFAULT_TRANSLATION_PROMPT }

    private val useEnglishLocale: Boolean
        get() = appPreferences.TRANSLATION_PROMPT_USE_ENGLISH_LOCALE.value

    override val available = true
    override val isUsingOnlineTranslation = true

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

    override suspend fun hasModelDownloaded(language: String): TranslationModelState? =
        models.firstOrNull { it.language == language }

    override fun getTranslator(source: String, target: String): TranslatorState {
        Log.d(TAG, "getTranslator: source=$source, target=$target")
        return TranslatorState(
            source = source,
            target = target,
            translate = { input -> translateSingle(input, source, target) }
        )
    }

    // ─── Single text translation ───────────────────────────────────────────────

    private suspend fun translateSingle(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = buildPrompt(sourceLanguage, targetLanguage)
        val userMessage = text

        val responseText = sendWithKeyRotation(systemPrompt, userMessage)
        responseText.trim().ifEmpty { text }
    }

    // ─── Batch translation ─────────────────────────────────────────────────────

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        Log.d(TAG, "translateBatch: ${texts.size} paragraphs, $sourceLanguage→$targetLanguage")

        val systemPrompt = buildPrompt(sourceLanguage, targetLanguage)

        // Number each paragraph: "1. text\n2. text\n..."
        val numberedTexts = texts.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val userMessage = "Translate the following numbered paragraphs. " +
                "Keep the numbering format exactly (1., 2., etc.). " +
                "Return ONLY the numbered translations:\n\n$numberedTexts"

        val responseText = sendWithKeyRotation(systemPrompt, userMessage)
        parseNumberedTranslations(responseText, texts)
    }

    // ─── Key rotation + HTTP ───────────────────────────────────────────────────

    /**
     * Sends a chat completion request, rotating through all available keys on
     * retriable errors (429). Throws a descriptive exception on permanent failures.
     */
    private suspend fun sendWithKeyRotation(
        systemPrompt: String,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val keys = apiKeys

        if (keys.isEmpty()) {
            throw IllegalStateException("OpenAI: No API keys configured. Please add your API key in Settings → Translation.")
        }

        val startIndex = keyIndex.getAndIncrement() % keys.size
        var lastException: Exception? = null

        // Try each key once, starting from current round-robin position
        for (attempt in keys.indices) {
            val currentKey = keys[(startIndex + attempt) % keys.size]
            val keyLabel = "key #${(startIndex + attempt) % keys.size + 1}"

            try {
                val response = sendRequest(systemPrompt, userMessage, currentKey)
                val code = response.code

                when {
                    code == 401 -> {
                        Log.w(TAG, "sendWithKeyRotation: 401 on $keyLabel, trying next")
                        response.body?.close()
                        lastException = IllegalStateException("OpenAI: Invalid API key ($keyLabel). Check your key in Settings.")
                        continue // try next key
                    }
                    code == 429 -> {
                        Log.w(TAG, "sendWithKeyRotation: 429 on $keyLabel, trying next")
                        response.body?.close()
                        lastException = IllegalStateException("OpenAI: Rate limit exceeded ($keyLabel).")
                        continue // try next key
                    }
                    code in 500..599 -> {
                        response.body?.close()
                        throw IOException("OpenAI: Server error ($code). Try again later.")
                    }
                    !response.isSuccessful -> {
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        response.body?.close()
                        throw IllegalStateException("OpenAI: Unexpected error ($code): $errorBody")
                    }
                    else -> {
                        // Success — advance the round-robin counter to the next key for the next call
                        keyIndex.set((startIndex + attempt + 1) % keys.size)
                        val body = response.body?.string()
                            ?: throw IllegalStateException("OpenAI: Empty response body")
                        return@withContext parseResponse(body)
                    }
                }
            } catch (e: IOException) {
                // Network/timeout errors — no point trying other keys
                Log.e(TAG, "sendWithKeyRotation: network error — ${e.message}")
                throw e
            }
        }

        // All keys failed
        throw lastException
            ?: IllegalStateException("OpenAI: All API keys failed. Check your keys in Settings.")
    }

    private fun sendRequest(
        systemPrompt: String,
        userMessage: String,
        apiKey: String
    ): okhttp3.Response {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
            put("temperature", 0.3)
            put("top_p", 1.0)
            put("stream", false) // В данном менеджере вы получаете текст целиком
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return client.newCall(request).execute()
    }

    private fun parseResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } else {
                throw IllegalStateException("OpenAI: No choices in response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseResponse: failed to parse — ${e.message}")
            throw IllegalStateException("OpenAI: Failed to parse response — ${e.message}")
        }
    }

    // ─── Prompt building ───────────────────────────────────────────────────────

    private fun buildPrompt(sourceLanguage: String, targetLanguage: String): String =
        buildSystemPrompt(systemPromptTemplate, sourceLanguage, targetLanguage, useEnglishLocale)

    // ─── Response parsing ──────────────────────────────────────────────────────

    /**
     * Parses a numbered translation response back to a map of original → translated.
     * Format expected: "1. translated text\n2. translated text\n..."
     */
    private fun parseNumberedTranslations(
        translatedText: String,
        originalTexts: List<String>
    ): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        val lines = translatedText.split("\n").filter { it.isNotBlank() }
        var currentIndex = 0
        var currentTranslation = StringBuilder()

        for (line in lines) {
            val numberMatch = Regex("""^(\d+)\.\s*""").find(line)
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

        // Fill any missing paragraphs with the original text
        originalTexts.forEach { text ->
            if (!translations.containsKey(text)) {
                Log.w(TAG, "parseNumberedTranslations: missing translation for paragraph, using original")
                translations[text] = text
            }
        }

        Log.d(TAG, "parseNumberedTranslations: ${translations.size}/${originalTexts.size} parsed")
        return translations
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    /**
     * OpenAI-совместимый провайдер не имеет встроенного определения языка.
     * Возвращает null — TranslationManagerComposite использует GoogleFree как fallback.
     */
    override suspend fun detectLanguage(text: String): String? = null

    companion object {
        private const val TAG = "TranslationOpenAI"
    }
}