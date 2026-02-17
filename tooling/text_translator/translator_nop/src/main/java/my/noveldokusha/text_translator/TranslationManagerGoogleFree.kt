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
                val endTime = System.currentTimeMillis() // <-- Замер времени после запроса

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
     * Tries to split evenly while respecting sentence structure
     */
    private fun splitTextIntoTwoParts(text: String): Pair<String, String> {
        // Split text into sentences using basic regex
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            return Pair("", "")
        }

        // Split point: first half gets slightly more if odd number
        val midIndex = (sentences.size + 1) / 2

        val firstPartSentences = sentences.take(midIndex)
        val secondPartSentences = sentences.drop(midIndex)

        val firstPartText = firstPartSentences.joinToString(" ")
        val secondPartText = secondPartSentences.joinToString(" ")

        return Pair(firstPartText, secondPartText)
    }

    /**
     * Translate all paragraphs using numbered paragraph format for reliable parsing.
     * Each paragraph is prefixed with a number to ensure Google Translate preserves the order.
     * Falls back to individual translation for any paragraphs that weren't translated.
     */
    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        Log.d(TAG, "translateBatch: translating ${texts.size} texts with numbered format")

        val translations = mutableMapOf<String, String>()

        // Create numbered list for translation - this ensures Google preserves order
        val numberedTexts = texts.mapIndexed { index, text ->
            "${index + 1}. $text"
        }.joinToString("\n\n")

        val totalChars = numberedTexts.length
        Log.d(TAG, "translateBatch: numbered format length = $totalChars characters")

        if (totalChars > 12000) {
            Log.w(TAG, "translateBatch: text too long ($totalChars chars), splitting into chunks")
            // Split into reasonable chunks
            val maxCharsPerChunk = 10000
            val chunks = mutableListOf<List<Pair<Int, String>>>() // index -> text
            var currentChunk = mutableListOf<Pair<Int, String>>()
            var currentLength = 0

            texts.forEachIndexed { index, text ->
                val numberedText = "${index + 1}. $text"
                if (currentLength + numberedText.length + 1 > maxCharsPerChunk && currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toList())
                    currentChunk = mutableListOf()
                    currentLength = 0
                }
                currentChunk.add(Pair(index, text))
                currentLength += numberedText.length + 1
            }
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
            }

            Log.d(TAG, "translateBatch: split into ${chunks.size} chunks, translating in parallel")
            val results = coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        val chunkText = chunk.map { "${it.first + 1}. ${it.second}" }.joinToString("\n\n")
                        try {
                            val translatedChunk = translateWithGoogleFree(chunkText, sourceLanguage, targetLanguage)
                            Pair(chunk.map { it.second }, translatedChunk)
                        } catch (e: Exception) {
                            Log.e(TAG, "translateBatch: chunk failed - ${e.message}")
                            Pair(chunk.map { it.second }, null)
                        }
                    }
                }.awaitAll()
            }

            results.forEach { (originalTexts, translatedText) ->
                if (translatedText != null) {
                    val parsed = parseNumberedTranslations(translatedText, originalTexts.size)
                    originalTexts.forEachIndexed { index, originalText ->
                        // Only save if translation was found, otherwise let fallback handle it
                        parsed[index]?.let { translated ->
                            translations[originalText] = translated
                        }
                    }
                }
                // If translatedText is null, don't save anything - fallback will handle it
            }
        } else {
            try {
                val translated = translateWithGoogleFree(numberedTexts, sourceLanguage, targetLanguage)
                Log.d(TAG, "translateBatch: translation successful, result length=${translated.length}")

                // Parse numbered translations back into map
                val parsedTranslations = parseNumberedTranslations(translated, texts.size)
                
                Log.d(TAG, "translateBatch: parsed ${parsedTranslations.size} translations (expected ${texts.size})")

                texts.forEachIndexed { index, originalText ->
                    // Only save if translation was found, otherwise let fallback handle it
                    parsedTranslations.getOrNull(index)?.let { translated ->
                        translations[originalText] = translated
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch: failed - ${e.message}", e)
                // Don't save anything - fallback will handle it
            }
        }

        // FALLBACK: Translate only paragraphs that were LOST during parsing
        // (not found in the numbered format results)
        val missingTexts = texts.filter { originalText ->
            !translations.containsKey(originalText)
        }
        
        if (missingTexts.isNotEmpty()) {
            Log.d(TAG, "translateBatch: ${missingTexts.size} paragraphs lost, translating individually")
            coroutineScope {
                missingTexts.map { originalText ->
                    async(Dispatchers.IO) {
                        val individualTranslation = translateWithGoogleFree(originalText, sourceLanguage, targetLanguage)
                        Pair(originalText, individualTranslation)
                    }
                }.awaitAll()
            }.forEach { (original, translated) ->
                translations[original] = translated
            }
        }

        // FINAL FALLBACK: Ensure no text is lost - use original as last resort
        texts.forEach { originalText ->
            if (!translations.containsKey(originalText)) {
                translations[originalText] = originalText
                Log.w(TAG, "translateBatch: using original text as fallback for: ${originalText.take(50)}...")
            }
        }

        Log.d(TAG, "translateBatch: completed, ${translations.size}/${texts.size} entries")
        return@withContext translations
    }

    /**
     * Parse numbered translations back into a list.
     * Google Translate preserves the numbering format "1. text", "2. text", etc.
     * Uses regex to find each numbered paragraph and extract the text after it.
     */
    private fun parseNumberedTranslations(translated: String, expectedCount: Int): List<String?> {
        val result = mutableListOf<String?>()
        
        // Use MULTILINE mode so ^ matches start of each line
        val multilineRegex = Regex("^(\\d+)[.)]\\s*(.+)$", RegexOption.MULTILINE)
        
        // Find all matches
        val matches = multilineRegex.findAll(translated).toList()
        
        Log.d(TAG, "parseNumberedTranslations: found ${matches.size} numbered paragraphs, expected $expectedCount")
        
        // Extract translations in order
        for (i in 0 until expectedCount) {
            val expectedNumber = i + 1
            // Find the match with this number
            val match = matches.find { match ->
                match.groupValues[1].toIntOrNull() == expectedNumber
            }
            
            if (match != null) {
                // Get the text after the number
                val text = match.groupValues[2].trim()
                result.add(text)
                Log.d(TAG, "parseNumberedTranslations: found paragraph $expectedNumber: ${text.take(50)}...")
            } else {
                // Try to find by position - maybe numbers are missing but we can use sequence
                if (i < matches.size) {
                    val fallbackMatch = matches[i]
                    val text = fallbackMatch.groupValues[2].trim()
                    result.add(text)
                    Log.w(TAG, "parseNumberedTranslations: using fallback for $expectedNumber: ${text.take(50)}...")
                } else {
                    result.add(null)
                    Log.w(TAG, "parseNumberedTranslations: no translation found for paragraph $expectedNumber")
                }
            }
        }
        
        // If we found more numbered paragraphs than expected, append them
        if (matches.size > expectedCount) {
            for (i in expectedCount until matches.size) {
                val text = matches[i].groupValues[2].trim()
                result.add(text)
                Log.d(TAG, "parseNumberedTranslations: extra paragraph ${i + 1}: ${text.take(50)}...")
            }
        }
        
        return result
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
