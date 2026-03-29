package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
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
import my.noveldokusha.network.interceptors.GLOBAL_USER_AGENT
import java.util.concurrent.TimeUnit

class TranslationManagerGoogleFree(
    private val coroutineScope: AppCoroutineScope
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
            translate = { input -> translateWithGoogleFree(input, source, target) ?: input }
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Определяет язык текста через Google Translate API.
     * Возвращает BCP-47 код языка (например "zh", "en", "ru") или null при ошибке.
     */
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
                .header("User-Agent", GLOBAL_USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val jsonArray = json.parseToJsonElement(body).jsonArray

                val detectedLang = jsonArray.getOrNull(2)?.jsonPrimitive?.contentOrNull

                if (detectedLang != null && detectedLang.length in 2..6) {
                    // Нормализуем zh-CN, zh-TW → zh, pt-BR → pt и т.д.
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
     * Возвращает null при ошибке (вместо строки с ошибкой).
     * Вызывающий код сам решает что делать — fallback на оригинал или не добавлять в map.
     */
    private suspend fun translateWithGoogleFree(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        retryCount: Int = 2
    ): String? = withContext(Dispatchers.IO) {
        if (text.length > 13000) {
            return@withContext translateLongText(text, sourceLanguage, targetLanguage)
        }

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
                        .addHeader("User-Agent", GLOBAL_USER_AGENT)
                        .build()
                } else {
                    val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                        .addQueryParameter("client", "gtx")
                        .addQueryParameter("sl", sourceLanguage)
                        .addQueryParameter("tl", targetLanguage)
                        .addQueryParameter("dt", "t")
                        .addQueryParameter("q", text)
                        .build()
                    okhttp3.Request.Builder().url(url).addHeader("User-Agent", GLOBAL_USER_AGENT).build()
                }

                val startTime = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

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

        val translations = mutableMapOf<String, String>()

        val chunks = mutableListOf<List<Pair<Int, String>>>()
        var currentChunk = mutableListOf<Pair<Int, String>>()
        var currentLen = 0
        val maxChunkChars = 8000

        for ((index, text) in texts.withIndex()) {
            val estimatedLen = text.length + 10
            if (currentLen + estimatedLen > maxChunkChars && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentLen = 0
            }
            currentChunk.add(index to text)
            currentLen += estimatedLen
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk)

        coroutineScope {
            chunks.map { chunk ->
                async {
                    val wrappedRequest = chunk.joinToString("\n\n") { (idx, text) -> "[$idx]\n$text" }
                    val translatedBody = translateWithGoogleFree(wrappedRequest, sourceLanguage, targetLanguage)

                    Log.d(TAG, "translateBatch: chunk size=${chunk.size}, response length=${translatedBody?.length ?: 0}")

                    if (translatedBody == null) {
                        // Весь chunk не переведён — пробуем каждый параграф отдельно
                        Log.w(TAG, "translateBatch: chunk translation failed, falling back to single translations")
                        chunk.forEach { (_, original) ->
                            val result = translateWithGoogleFree(original, sourceLanguage, targetLanguage)
                            if (result != null) translations[original] = result
                        }
                        return@async
                    }

                    chunk.forEach { (idx, original) ->
                        // Мягкий regex: допускает пробелы и точку внутри маркера [N] или [N.]
                        val regex = Regex(
                            """^\[\s*$idx\s*\.?\]\s*\n?(.*?)(?=\n*\[\s*\d+\s*\.?\]|\z)""",
                            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
                        )
                        val match = regex.find(translatedBody)
                        if (match != null) {
                            val result = match.groupValues[1].trim()
                            if (result.isNotEmpty()) {
                                translations[original] = result
                            } else {
                                Log.w(TAG, "Marker [$idx] found but empty, falling back to single translation")
                                val fallback = translateWithGoogleFree(original, sourceLanguage, targetLanguage)
                                if (fallback != null) translations[original] = fallback
                            }
                        } else {
                            Log.w(TAG, "Marker [$idx] not found in response, falling back to single translation")
                            val fallback = translateWithGoogleFree(original, sourceLanguage, targetLanguage)
                            if (fallback != null) translations[original] = fallback
                        }
                    }

                    val missing = chunk.count { (_, original) -> !translations.containsKey(original) }
                    if (missing > 0) {
                        Log.w(TAG, "translateBatch: $missing/${chunk.size} paragraphs still missing after fallback")
                    }
                }
            }.awaitAll()
        }

        Log.d(TAG, "translateBatch: total=${texts.size}, translated=${translations.size}, missing=${texts.size - translations.size}")
        translations
    }

    private suspend fun translateLongText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String? = withContext(Dispatchers.IO) {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotEmpty() }
        if (sentences.size <= 1) return@withContext null

        val mid = sentences.size / 2
        val firstPart = sentences.take(mid).joinToString(" ")
        val secondPart = sentences.drop(mid).joinToString(" ")

        coroutineScope {
            val d1 = async { translateWithGoogleFree(firstPart, sourceLanguage, targetLanguage) }
            val d2 = async { translateWithGoogleFree(secondPart, sourceLanguage, targetLanguage) }
            val r1 = d1.await()
            val r2 = d2.await()
            if (r1 != null && r2 != null) "$r1 $r2" else null
        }
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object { private const val TAG = "TranslationGoogleFree" }
}