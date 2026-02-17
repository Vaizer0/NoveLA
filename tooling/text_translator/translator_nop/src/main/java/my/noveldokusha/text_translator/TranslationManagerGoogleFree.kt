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
import java.util.concurrent.ConcurrentHashMap
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

    private val translationCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

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
            translate = { input -> translateWithGoogleFree(input, source, target) }
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun translateWithGoogleFree(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        retryCount: Int = 2
    ): String = withContext(Dispatchers.IO) {
        val cacheKey = "$sourceLanguage-$targetLanguage:$text"
        translationCache[cacheKey]?.let { return@withContext it }

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
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()
                } else {
                    val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                        .addQueryParameter("client", "gtx")
                        .addQueryParameter("sl", sourceLanguage)
                        .addQueryParameter("tl", targetLanguage)
                        .addQueryParameter("dt", "t")
                        .addQueryParameter("q", text)
                        .build()
                    okhttp3.Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
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
                        translationCache[cacheKey] = result
                        Log.d(TAG, "Translated ${text.length} chars in ${System.currentTimeMillis() - startTime}ms")
                        return@withContext result
                    }
                }
            } catch (e: Exception) { lastException = e }
            if (attempt < retryCount - 1) kotlinx.coroutines.delay(200L * (attempt + 1))
        }
        "[Translation error: ${lastException?.message?.take(50)}]"
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        val translations = mutableMapOf<String, String>()
        val textsToTranslate = mutableListOf<Pair<Int, String>>()

        texts.forEachIndexed { index, text ->
            val cached = translationCache["$sourceLanguage-$targetLanguage:$text"]
            if (cached != null) translations[text] = cached
            else textsToTranslate.add(index to text)
        }

        if (textsToTranslate.isEmpty()) return@withContext translations

        val chunks = mutableListOf<List<Pair<Int, String>>>()
        var currentChunk = mutableListOf<Pair<Int, String>>()
        var currentLen = 0
        val maxChunkChars = 10000

        for (item in textsToTranslate) {
            val estimatedLen = item.second.length + 20
            if (currentLen + estimatedLen > maxChunkChars && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentLen = 0
            }
            currentChunk.add(item)
            currentLen += estimatedLen
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk)

        coroutineScope {
            chunks.map { chunk ->
                async {
                    val wrappedRequest = chunk.joinToString("\n") { (idx, text) -> "<n$idx>$text</n$idx>" }
                    val translatedBody = translateWithGoogleFree(wrappedRequest, sourceLanguage, targetLanguage)

                    chunk.forEach { (idx, original) ->
                        val regex = Regex("<n$idx>(.*?)</n$idx>", RegexOption.DOT_MATCHES_ALL)
                        val match = regex.find(translatedBody)
                        if (match != null) {
                            val result = match.groupValues[1].trim()
                            translations[original] = result
                            translationCache["$sourceLanguage-$targetLanguage:$original"] = result
                        }
                    }
                }
            }.awaitAll()
        }

        val stillMissing = texts.filter { !translations.containsKey(it) }
        if (stillMissing.isNotEmpty()) {
            coroutineScope {
                stillMissing.map { original ->
                    async {
                        val res = translateWithGoogleFree(original, sourceLanguage, targetLanguage)
                        original to res
                    }
                }.awaitAll().forEach { (orig, trans) ->
                    translations[orig] = if (trans.contains("[Translation error")) orig else trans
                }
            }
        }

        translations
    }

    private suspend fun translateLongText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String = withContext(Dispatchers.IO) {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotEmpty() }
        if (sentences.size <= 1) return@withContext text

        val mid = sentences.size / 2
        val firstPart = sentences.take(mid).joinToString(" ")
        val secondPart = sentences.drop(mid).joinToString(" ")

        coroutineScope {
            val d1 = async { translateWithGoogleFree(firstPart, sourceLanguage, targetLanguage) }
            val d2 = async { translateWithGoogleFree(secondPart, sourceLanguage, targetLanguage) }
            "${d1.await()} ${d2.await()}"
        }
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    fun invalidateCacheFor(sourceLanguage: String, targetLanguage: String, text: String? = null) {
        val prefix = "$sourceLanguage-$targetLanguage:"
        if (text == null) {
            translationCache.keys.filter { it.startsWith(prefix) }.forEach { translationCache.remove(it) }
        } else {
            translationCache.remove("$prefix$text")
        }
    }

    companion object { private const val TAG = "TranslationGoogleFree" }
}