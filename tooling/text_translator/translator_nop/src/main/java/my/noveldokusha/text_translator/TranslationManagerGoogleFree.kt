package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.text_translator.domain.TranslationManager
import my.noveldokusha.text_translator.domain.TranslationModelState
import my.noveldokusha.text_translator.domain.TranslatorState
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.interceptors.resolveUserAgent
import java.util.concurrent.TimeUnit

class TranslationManagerGoogleFree(
    private val coroutineScope: AppCoroutineScope,
    private val appPreferences: AppPreferences
) : TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override val available = true
    override val isUsingOnlineTranslation = true

    override val models = mutableStateListOf<TranslationModelState>().apply {
        val supportedLanguages = listOf(
            "en", "zh", "ja", "ko", "es", "fr", "de", "it", "pt", "ru",
            "ar", "hi", "th", "vi", "id", "tr", "pl", "nl", "sv", "da",
            "fi", "no", "cs", "el", "he", "ro", "hu", "uk", "bg", "hr"
        )
        addAll(supportedLanguages.map { lang ->
            TranslationModelState(language = lang, available = true, downloading = false, downloadingFailed = false)
        })
    }

    override suspend fun hasModelDownloaded(language: String): TranslationModelState? {
        return models.firstOrNull { it.language == language }
    }

    override fun getTranslator(source: String, target: String): TranslatorState {
        Log.d(TAG, "getTranslator: source=$source, target=$target")
        return TranslatorState(
            source = source,
            target = target,
            translate = { input ->
                translateWithGoogleFree(input, source, target)
                    ?: throw IllegalStateException("Google Translate: Failed to translate. Check your internet connection.")
            }
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun detectLanguage(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        try {
            val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", "en")
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text.take(100))
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", resolveUserAgent(appPreferences))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body.string().ifBlank { return@withContext null }
                val jsonArray = json.parseToJsonElement(body).jsonArray

                val detectedLang = jsonArray.getOrNull(2)?.jsonPrimitive?.contentOrNull

                if (detectedLang != null && detectedLang.length in 2..6) {
                    detectedLang.substringBefore("-")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns null on failure — callers decide whether to throw or fallback.
     */
    private suspend fun translateWithGoogleFree(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        retryCount: Int = 2
    ): String? = withContext(Dispatchers.IO) {

        var lastException: Exception? = null
        repeat(retryCount) { attempt ->
            try {
                val request = if (text.length > 500) {
                    val formBody = okhttp3.FormBody.Builder()
                        .add("client", "gtx")
                        .add("sl", sourceLanguage)
                        .add("tl", targetLanguage)
                        .add("dt", "t")
                        .add("q", text)
                        .build()
                    okhttp3.Request.Builder()
                        .url("https://translate.googleapis.com/translate_a/single")
                        .post(formBody)
                        .addHeader("User-Agent", resolveUserAgent(appPreferences))
                        .build()
                } else {
                    val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                        .addQueryParameter("client", "gtx")
                        .addQueryParameter("sl", sourceLanguage)
                        .addQueryParameter("tl", targetLanguage)
                        .addQueryParameter("dt", "t")
                        .addQueryParameter("q", text)
                        .build()
                    okhttp3.Request.Builder().url(url).addHeader("User-Agent", resolveUserAgent(appPreferences)).build()
                }

                val startTime = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val responseBody = response.body.string()

                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val jsonElement = json.parseToJsonElement(responseBody)
                    val result = buildString {
                        jsonElement.jsonArray.getOrNull(0)?.jsonArray?.forEach { item ->
                            append(item.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: "")
                        }
                    }.trim()

                    if (result.isNotEmpty()) {
                        Log.d(TAG, "Translated ${text.length} chars in ${System.currentTimeMillis() - startTime}ms")
                        return@withContext result
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
            if (attempt < retryCount - 1) kotlinx.coroutines.delay(200L * (attempt + 1))
        }
        Log.w(TAG, "translateWithGoogleFree: failed after $retryCount attempts - ${lastException?.message?.take(50)}")
        null
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        val normalizedTexts = texts.filter { it.isNotBlank() }
        if (normalizedTexts.isEmpty()) return@withContext emptyMap()

        // Flatten all texts into a single paragraph list, tracking which original text each belongs to.
        // This mirrors PA's approach: one translateChunks call handles everything sequentially.
        val boundaries = mutableListOf<IntRange>()
        val allParagraphs = mutableListOf<String>()
        for (text in normalizedTexts) {
            val lines = text.split("\n").filter { it.isNotBlank() }
            val start = allParagraphs.size
            allParagraphs.addAll(lines)
            boundaries.add(start until start + lines.size)
        }

        val translatedAll = translateChunks(allParagraphs, sourceLanguage, targetLanguage)

        val result = mutableMapOf<String, String>()
        for ((i, text) in normalizedTexts.withIndex()) {
            val range = boundaries[i]
            if (range.isEmpty()) { result[text] = text; continue }
            val safeEnd = range.last.coerceAtMost(translatedAll.size - 1)
            if (safeEnd < range.first) { result[text] = text; continue }
            val translatedLines = translatedAll.subList(range.first, safeEnd + 1)
            result[text] = if (translatedLines.isNotEmpty()) translatedLines.joinToString("\n") else text
        }

        Log.d(TAG, "translateBatch: total=${normalizedTexts.size}, translated=${result.size}")

        if (result.isEmpty() && normalizedTexts.isNotEmpty()) {
            throw IllegalStateException("Google Translate: Failed to translate. Check your internet connection.")
        }

        result
    }

    /**
     * Splits paragraphs into 8 000-char chunks and translates them sequentially
     * with a short delay between requests to avoid rate-limiting the public endpoint.
     * Each chunk is sent as a single plain-text request with \n separators.
     */
    private suspend fun translateChunks(
        paragraphs: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String> {
        val maxChunkChars = 8_000
        val result = paragraphs.toMutableList()

        data class Chunk(val indices: List<Int>, val text: String)

        val chunks = mutableListOf<Chunk>()
        val currentIndices = mutableListOf<Int>()
        val currentParts = mutableListOf<String>()
        var currentLen = 0

        for ((i, para) in paragraphs.withIndex()) {
            if (currentLen > 0 && currentLen + para.length + 1 > maxChunkChars) {
                chunks.add(Chunk(currentIndices.toList(), currentParts.joinToString("\n")))
                currentIndices.clear()
                currentParts.clear()
                currentLen = 0
            }
            currentIndices.add(i)
            currentParts.add(para)
            currentLen += para.length + 1
        }
        if (currentParts.isNotEmpty()) {
            chunks.add(Chunk(currentIndices.toList(), currentParts.joinToString("\n")))
        }

        Log.d(TAG, "translateChunks: ${paragraphs.size} paragraphs → ${chunks.size} chunks, $sourceLanguage→$targetLanguage")

        var failedChunks = 0

        for ((idx, chunk) in chunks.withIndex()) {
            if (idx > 0) kotlinx.coroutines.delay(300L)

            val translated = translateWithGoogleFree(chunk.text, sourceLanguage, targetLanguage)

            if (translated == null) {
                Log.e(TAG, "Chunk ${idx + 1}/${chunks.size} failed")
                failedChunks++
                continue
            }

            val translatedLines = translated.split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val minSize = minOf(translatedLines.size, chunk.indices.size)
            for (pos in 0 until minSize) {
                result[chunk.indices[pos]] = translatedLines[pos]
            }

            if (translatedLines.size != chunk.indices.size) {
                Log.w(TAG, "Chunk ${idx + 1}: expected ${chunk.indices.size} paragraphs, got ${translatedLines.size}")
            }
        }

        if (failedChunks == chunks.size && chunks.isNotEmpty()) {
            throw IllegalStateException("Google Translate: Failed to translate. Check your internet connection.")
        }

        return result
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object { private const val TAG = "TranslationGoogleFree" }
}