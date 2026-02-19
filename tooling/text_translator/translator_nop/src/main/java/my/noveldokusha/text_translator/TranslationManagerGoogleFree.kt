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

        // Split texts into chunks for batch translation
        // Используем числовые маркеры [N] вместо XML-тегов — Google их не искажает
        val chunks = mutableListOf<List<Pair<Int, String>>>()
        var currentChunk = mutableListOf<Pair<Int, String>>()
        var currentLen = 0
        val maxChunkChars = 8000 // Снижено с 10000 для надёжности

        for ((index, text) in texts.withIndex()) {
            val estimatedLen = text.length + 10 // маркер [N]\n короче XML-тега
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
                    // Формат: "[0]\nтекст\n\n[1]\nтекст\n\n..."
                    val wrappedRequest = chunk.joinToString("\n\n") { (idx, text) -> "[$idx]\n$text" }
                    val translatedBody = translateWithGoogleFree(wrappedRequest, sourceLanguage, targetLanguage)

                    // Логируем количество переводов для отладки
                    Log.d(TAG, "translateBatch: chunk size=${chunk.size}, response length=${translatedBody.length}")

                    chunk.forEach { (idx, original) ->
                        // Ищем содержимое между маркером [idx] и следующим маркером или концом строки
                        val regex = Regex(
                            """^\[$idx\]\s*\n?(.*?)(?=\n*\[\d+\]|\z)""",
                            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
                        )
                        val match = regex.find(translatedBody)
                        if (match != null) {
                            val result = match.groupValues[1].trim()
                            if (result.isNotEmpty()) {
                                translations[original] = result
                            } else {
                                // Маркер найден, но содержимое пустое — fallback
                                Log.w(TAG, "Marker [$idx] found but empty, falling back to single translation")
                                translations[original] = translateWithGoogleFree(original, sourceLanguage, targetLanguage)
                            }
                        } else {
                            // Маркер не найден (искажён переводчиком) — переводим параграф отдельно
                            Log.w(TAG, "Marker [$idx] not found in response, falling back to single translation")
                            translations[original] = translateWithGoogleFree(original, sourceLanguage, targetLanguage)
                        }
                    }

                    // Логируем если какие-то параграфы не переведены
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

    companion object { private const val TAG = "TranslationGoogleFree" }
}