package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

/**
 * Translation manager using free Google Translate API
 * No API key required - uses unofficial endpoint
 * Limit: ~13-14k characters per request
 */
class TranslationManagerGoogleFree(
    private val coroutineScope: AppCoroutineScope
) : TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override val available = true
    override val isUsingOnlineTranslation = true

    // Cache for translations
    private val translationCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // Google Translate supports many languages
    override val models = mutableStateListOf<TranslationModelState>().apply {
        val supportedLanguages = listOf(
            "en", "zh", "ja", "ko", "es", "fr", "de", "it", "pt", "ru",
            "ar", "hi", "th", "vi", "id", "tr", "pl", "nl", "sv", "da",
            "fi", "no", "cs", "el", "he", "ro", "hu", "uk", "bg", "hr"
        )

        addAll(supportedLanguages.map { lang ->
            TranslationModelState(
                language = lang,
                available = true, // Always available via API
                downloading = false,
                downloadingFailed = false
            )
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
        translationCache[cacheKey]?.let {
            Log.d(TAG, "translateWithGoogleFree: using cached translation")
            return@withContext it
        }

        Log.d(TAG, "translateWithGoogleFree: starting translation (length=${text.length})")

        val maxChars = 13000
        if (text.length > maxChars) {
            Log.d(TAG, "translateWithGoogleFree: text too long, splitting...")
            return@withContext translateLongText(text, sourceLanguage, targetLanguage)
        }

        var lastException: Exception? = null
        repeat(retryCount) { attempt ->
            try {
                Log.d(TAG, "translateWithGoogleFree: attempt ${attempt + 1}/$retryCount")

                val request = if (text.length > 500) {
                    // Use POST for large texts
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
                        .addHeader("Content-Type", "application/x-www-form-urlencoded")
                        .build()
                } else {
                    // Use GET with safe HttpUrl builder
                    val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                        .addQueryParameter("client", "gtx")
                        .addQueryParameter("sl", sourceLanguage)
                        .addQueryParameter("tl", targetLanguage)
                        .addQueryParameter("dt", "t")
                        .addQueryParameter("q", text)
                        .build()

                    okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()
                }
                val startTime = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val endTime = System.currentTimeMillis()

                Log.d(TAG, "Network request took ${endTime - startTime} ms on Android ${android.os.Build.VERSION.SDK_INT}")

                val responseBody = response.body?.string() ?: ""

                Log.d(TAG, "translateWithGoogleFree: response code=${response.code}, bodyLength=${responseBody.length}")

                if (response.isSuccessful && responseBody.isNotEmpty()) {

                    try {
                        val jsonElement = json.parseToJsonElement(responseBody)

                        val result = buildString {
                            val mainArray = jsonElement.jsonArray.getOrNull(0)?.jsonArray
                            if (mainArray != null) {
                                for (item in mainArray) {
                                    val part = item.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: ""
                                    append(part)
                                }
                            }
                        }.trim()

                        if (result.isNotEmpty()) {
                            Log.d(TAG, "translateWithGoogleFree: success, result length=${result.length}")
                            translationCache[cacheKey] = result
                            return@withContext result
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "translateWithGoogleFree: JSON parsing error with Kotlinx Serialization", e)
                    }
                }

                Log.w(TAG, "translateWithGoogleFree: empty or failed response (code=${response.code})")

            } catch (e: Exception) {
                Log.e(TAG, "translateWithGoogleFree: error on attempt ${attempt + 1} - ${e.message}", e)
                lastException = e
            }

            if (attempt < retryCount - 1) {
                kotlinx.coroutines.delay(200L * (attempt + 1))
            }
        }

        // If all retries fail
        return@withContext "[Translation error: ${lastException?.message?.take(50) ?: "unknown"}]"
    }

    private suspend fun translateLongText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "translateLongText: splitting text (${text.length} chars)")

        val (firstPart, secondPart) = splitTextIntoTwoParts(text)
        Log.d(TAG, "translateLongText: part1=${firstPart.length} chars, part2=${secondPart.length} chars")

        if (firstPart.isEmpty() && secondPart.isEmpty()) {
            return@withContext ""
        }

        val (translatedFirst, translatedSecond) = coroutineScope {
            val deferredFirst = async {
                if (firstPart.isNotEmpty()) {
                    translateWithGoogleFree(firstPart, sourceLanguage, targetLanguage)
                } else ""
            }

            val deferredSecond = async {
                if (secondPart.isNotEmpty()) {
                    translateWithGoogleFree(secondPart, sourceLanguage, targetLanguage)
                } else ""
            }

            Pair(deferredFirst.await(), deferredSecond.await())
        }
        return@withContext if (translatedFirst.isNotEmpty() && translatedSecond.isNotEmpty()) {
            "$translatedFirst $translatedSecond"
        } else {
            (translatedFirst + translatedSecond).trim()
        }
    }

    /**
     * Split text into two parts at sentence boundaries
     */
    private fun splitTextIntoTwoParts(text: String): Pair<String, String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            return Pair("", "")
        }

        val midIndex = (sentences.size + 1) / 2

        val firstPartText = sentences.take(midIndex).joinToString(" ")
        val secondPartText = sentences.drop(midIndex).joinToString(" ")

        return Pair(firstPartText, secondPartText)
    }

    /**
     * Check if text looks like a sentence (not a name/title)
     * Names/titles are short or don't have spaces
     */
    private fun looksLikeSentence(text: String): Boolean {
        return text.contains(" ") && text.length > 10
    }

    /**
     * Check if a translation needs individual fallback.
     * Returns true if:
     * - No translation exists (null)
     * - Translation equals original AND it looks like a sentence
     */
    private fun needsFallback(original: String, translated: String?): Boolean {
        if (translated == null) return true
        if (translated == original && looksLikeSentence(original)) return true
        return false
    }

    /**
     * Translate all paragraphs using batch translation with individual fallback.
     * 
     * 1. Batch translate all paragraphs together (separated by \n\n)
     * 2. If paragraph count matches - great!
     * 3. If not - use individual fallback for problematic paragraphs only
     * 4. Check each result: if translation == original AND looks like sentence -> individual fallback
     */
    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        Log.d(TAG, "translateBatch: translating ${texts.size} paragraphs")

        val translations = mutableMapOf<String, String>()

        // Combine all texts with double newline separator
        val combinedText = texts.joinToString("\n\n")
        val totalChars = combinedText.length

        Log.d(TAG, "translateBatch: combined ${texts.size} paragraphs into $totalChars characters")

        if (totalChars > 12000) {
            // Split into chunks for large texts
            translateBatchInChunks(texts, sourceLanguage, targetLanguage, translations)
        } else {
            // Single batch request
            translateBatchSingle(texts, combinedText, sourceLanguage, targetLanguage, translations)
        }

        // FALLBACK: Check for problematic translations and retry individually
        val needsFallback = texts.filter { originalText ->
            needsFallback(originalText, translations[originalText])
        }

        if (needsFallback.isNotEmpty()) {
            Log.d(TAG, "translateBatch: ${needsFallback.size} paragraphs need individual fallback")
            
            needsFallback.forEachIndexed { index, originalText ->
                // Add delay between individual requests to avoid rate limiting
                delay(100L * (index + 1))
                
                try {
                    val individualTranslation = translateWithGoogleFree(originalText, sourceLanguage, targetLanguage)
                    // Only update if the individual translation is different from original
                    if (individualTranslation != originalText) {
                        translations[originalText] = individualTranslation
                        Log.d(TAG, "translateBatch: fallback succeeded for: ${originalText.take(30)}...")
                    } else {
                        Log.w(TAG, "translateBatch: fallback also returned original for: ${originalText.take(30)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "translateBatch: fallback failed for: ${originalText.take(30)}... - ${e.message}")
                }
            }
        }

        // FINAL: Ensure all texts have a value (use original as last resort)
        texts.forEach { originalText ->
            if (!translations.containsKey(originalText)) {
                translations[originalText] = originalText
                Log.w(TAG, "translateBatch: using original for: ${originalText.take(30)}...")
            }
        }

        Log.d(TAG, "translateBatch: completed, ${translations.size}/${texts.size} entries")
        return@withContext translations
    }

    /**
     * Translate a large batch by splitting into chunks
     */
    private suspend fun translateBatchInChunks(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        translations: MutableMap<String, String>
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "translateBatchInChunks: splitting into chunks")

        val maxCharsPerChunk = 10000
        val chunks = mutableListOf<List<String>>()
        var currentChunk = mutableListOf<String>()
        var currentLength = 0

        texts.forEach { text ->
            if (currentLength + text.length + 2 > maxCharsPerChunk && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toList())
                currentChunk = mutableListOf()
                currentLength = 0
            }
            currentChunk.add(text)
            currentLength += text.length + 2
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        Log.d(TAG, "translateBatchInChunks: split into ${chunks.size} chunks")

        // Translate chunks sequentially with delay to avoid rate limiting
        chunks.forEachIndexed { chunkIndex, chunk ->
            delay(200L * chunkIndex) // Delay between chunks
            
            val chunkText = chunk.joinToString("\n\n")
            try {
                val translatedChunk = translateWithGoogleFree(chunkText, sourceLanguage, targetLanguage)
                val translatedParagraphs = translatedChunk.split("\n\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                Log.d(TAG, "translateBatchInChunks: chunk ${chunkIndex + 1} - got ${translatedParagraphs.size} paragraphs, expected ${chunk.size}")

                if (translatedParagraphs.size == chunk.size) {
                    // Perfect match
                    chunk.forEachIndexed { index, originalText ->
                        translations[originalText] = translatedParagraphs[index]
                    }
                } else {
                    // Mismatch - save what we can, rest will go to fallback
                    chunk.forEachIndexed { index, originalText ->
                        translatedParagraphs.getOrNull(index)?.let { translated ->
                            translations[originalText] = translated
                        }
                        // If not found, don't save - fallback will handle it
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateBatchInChunks: chunk ${chunkIndex + 1} failed - ${e.message}")
                // Don't save anything - fallback will handle it
            }
        }
    }

    /**
     * Translate a single batch (under 12k characters)
     */
    private suspend fun translateBatchSingle(
        texts: List<String>,
        combinedText: String,
        sourceLanguage: String,
        targetLanguage: String,
        translations: MutableMap<String, String>
    ) = withContext(Dispatchers.IO) {
        try {
            val translated = translateWithGoogleFree(combinedText, sourceLanguage, targetLanguage)
            Log.d(TAG, "translateBatchSingle: result length=${translated.length}")

            val translatedParagraphs = translated.split("\n\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            Log.d(TAG, "translateBatchSingle: got ${translatedParagraphs.size} paragraphs, expected ${texts.size}")

            if (translatedParagraphs.size == texts.size) {
                // Perfect match - save all
                texts.forEachIndexed { index, originalText ->
                    translations[originalText] = translatedParagraphs[index]
                }
            } else {
                // Mismatch - save what we can, rest will go to fallback
                Log.w(TAG, "translateBatchSingle: paragraph count mismatch")
                texts.forEachIndexed { index, originalText ->
                    translatedParagraphs.getOrNull(index)?.let { translated ->
                        translations[originalText] = translated
                    }
                    // If not found, don't save - fallback will handle it
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "translateBatchSingle: failed - ${e.message}", e)
            // Don't save anything - fallback will handle it
        }
    }

    override fun downloadModel(language: String) {
        // No-op for online translation
    }

    override fun removeModel(language: String) {
        // No-op for online translation
    }

    /**
     * Invalidate cached translation(s)
     */
    fun invalidateCacheFor(sourceLanguage: String, targetLanguage: String, text: String? = null) {
        Log.d(TAG, "invalidateCacheFor: source=$sourceLanguage, target=$targetLanguage")
        if (text == null) {
            val prefix = "$sourceLanguage-$targetLanguage:"
            val keysToRemove = translationCache.keys.filter { it.startsWith(prefix) }
            Log.d(TAG, "invalidateCacheFor: clearing ${keysToRemove.size} cached entries")
            keysToRemove.forEach { translationCache.remove(it) }
        } else {
            val key = "$sourceLanguage-$targetLanguage:$text"
            val removed = translationCache.remove(key)
            Log.d(TAG, "invalidateCacheFor: ${if (removed != null) "cleared" else "no entry found for"} specific key")
        }
    }

    companion object {
        private const val TAG = "TranslationGoogleFree"
    }
}