package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.TimeUnit

/**
 * Translation manager using translate-pa.googleapis.com/v1/translateHtml —
 * the same API used by WtrLab plugin. Sends HTML-wrapped paragraphs which gives
 * significantly better quality than the plain-text translate.googleapis.com endpoint.
 *
 * Paragraph strategy (mirrors wtr-lab website exactly):
 *   - Join paragraphs with <br> (NOT <p> tags)
 *   - After translation split result back on <br>
 *   - No Html.fromHtml() needed — avoids tag stripping and paragraph misalignment
 *
 * Key management strategy:
 * 1. Use cached key if it was verified less than KEY_CACHE_DURATION_MS ago
 * 2. Otherwise try each key from TRANSLATION_GOOGLE_PA_API_KEYS (newline-separated)
 * 3. If none work — fetch a fresh key from wtr-lab.com and add it to the list
 * 4. If wtr-lab also fails — throw error
 *
 * Concurrency: parallel callers share a single in-flight Deferred<String> so only
 * one HTTP fetch runs at a time; all others await its result (Mutex + keyFetchJob).
 */
class TranslationManagerGooglePA(
    private val coroutineScope: AppCoroutineScope,
    private val appPreferences: AppPreferences
) : TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override val available = true
    override val isUsingOnlineTranslation = true

    private val translateUrl = "https://translate-pa.googleapis.com/v1/translateHtml"

    // How long a verified key is trusted without re-checking (24 hours)
    private val KEY_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

    // Regex to find the key in wtr-lab JS bundle
    private val keyHeaderRegex = Regex(""""X-Goog-API-Key"\s*:\s*"([^"]+)"""")

    // ─── Concurrency guard for key fetching ────────────────────────────────────

    private val keyFetchMutex = Mutex()

    /** Non-null while a key fetch is in progress; all late arrivals await this. */
    private var keyFetchJob: Deferred<String>? = null

    // ─── Models ────────────────────────────────────────────────────────────────

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
            translate = { input -> translateSingle(input, source, target) ?: input }
        )
    }

    // ─── Key management ────────────────────────────────────────────────────────

    /**
     * Returns a working API key.
     *
     * Fast path: cache hit — no locking needed.
     * Slow path: acquire [keyFetchMutex], re-check cache (another coroutine may have
     * just refreshed it), then either join the existing [keyFetchJob] or start a new one.
     * All concurrent callers share a single [Deferred] so only one HTTP round-trip happens.
     */
    private suspend fun getApiKey(): String = coroutineScope {
        // Fast path — no lock needed
        val cachedKey = appPreferences.TRANSLATION_GOOGLE_PA_CACHED_KEY.value
        val lastChecked = appPreferences.TRANSLATION_GOOGLE_PA_KEY_LAST_CHECKED.value
        val now = System.currentTimeMillis()
        if (cachedKey.isNotBlank() && (now - lastChecked) < KEY_CACHE_DURATION_MS) {
            Log.d(TAG, "getApiKey: using cached key (age=${(now - lastChecked) / 1000}s)")
            return@coroutineScope cachedKey
        }

        // Slow path — one fetch at a time
        val deferred: Deferred<String> = keyFetchMutex.withLock {
            // Double-checked: another coroutine may have refreshed the cache while we waited
            val freshKey = appPreferences.TRANSLATION_GOOGLE_PA_CACHED_KEY.value
            val freshChecked = appPreferences.TRANSLATION_GOOGLE_PA_KEY_LAST_CHECKED.value
            if (freshKey.isNotBlank() && (System.currentTimeMillis() - freshChecked) < KEY_CACHE_DURATION_MS) {
                Log.d(TAG, "getApiKey: cache refreshed while waiting for mutex")
                return@coroutineScope freshKey
            }

            // Re-use in-flight job if one already started
            keyFetchJob?.let { existing ->
                Log.d(TAG, "getApiKey: joining existing fetch job")
                return@withLock existing
            }

            // We are first — launch the actual fetch
            Log.d(TAG, "getApiKey: starting new fetch job")
            val job = async(Dispatchers.IO) { fetchAndCacheKey() }
            keyFetchJob = job
            job
        }

        try {
            deferred.await()
        } finally {
            // Clear the shared job reference once all waiters have received the result
            keyFetchMutex.withLock {
                if (keyFetchJob === deferred) keyFetchJob = null
            }
        }
    }

    /**
     * Validates keys from preferences, then falls back to wtr-lab.
     * Called from exactly one coroutine at a time (enforced by [keyFetchMutex]).
     */
    private suspend fun fetchAndCacheKey(): String {
        val now = System.currentTimeMillis()

        // 1. Try each key from preferences
        val keys = appPreferences.TRANSLATION_GOOGLE_PA_API_KEYS.value
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        for (key in keys) {
            if (checkKey(key)) {
                Log.d(TAG, "fetchAndCacheKey: found working key from preferences")
                cacheKey(key, now)
                return key
            }
        }

        // 2. Fetch from wtr-lab
        Log.d(TAG, "fetchAndCacheKey: no working key found, fetching from wtr-lab")
        val fetchedKey = fetchKeyFromWtrLab()
        if (fetchedKey != null) {
            Log.d(TAG, "fetchAndCacheKey: got key from wtr-lab, adding to list")
            addKeyToPreferences(fetchedKey)
            cacheKey(fetchedKey, now)
            return fetchedKey
        }

        error("No working Google PA API key found")
    }

    /**
     * Checks if a key works by sending a minimal test request.
     */
    private suspend fun checkKey(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = listOf(listOf("<p>test</p>", "en", "en"), "wt_lib")
            val body = Gson().toJson(payload).toRequestBody("application/json+protobuf".toMediaType())
            val request = Request.Builder()
                .url(translateUrl)
                .addHeader("X-Goog-Api-Key", key)
                .addHeader("Origin", "https://translate.google.com")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.body?.close()
            Log.d(TAG, "checkKey: ${key.take(12)}… → HTTP ${response.code}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "checkKey failed: ${e.message}")
            false
        }
    }

    private fun cacheKey(key: String, timestamp: Long) {
        appPreferences.TRANSLATION_GOOGLE_PA_CACHED_KEY.value = key
        appPreferences.TRANSLATION_GOOGLE_PA_KEY_LAST_CHECKED.value = timestamp
    }

    /**
     * Adds a newly fetched key to the top of the user's key list (deduplicating).
     */
    private fun addKeyToPreferences(key: String) {
        val existing = appPreferences.TRANSLATION_GOOGLE_PA_API_KEYS.value
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != key }
        appPreferences.TRANSLATION_GOOGLE_PA_API_KEYS.value =
            (listOf(key) + existing).joinToString("\n")
    }

    /**
     * Fetches a working key from wtr-lab.com.
     *
     * wtr-lab uses Turbopack, so JS bundles are NOT listed in _buildManifest.js.
     * Instead we find them directly from <script src="..."> tags in the page HTML.
     *
     * Strategy:
     * 1. Load main page HTML
     * 2. Search for key directly in inline scripts (fast path)
     * 3. Collect all <script src=".../_next/..."> URLs
     * 4. Load those scripts IN PARALLEL and return the first key found
     */
    private suspend fun fetchKeyFromWtrLab(): String? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Load ranking page to find a novel link
            val rankingHtml = client.newCall(
                Request.Builder().url("https://wtr-lab.com/en/ranking/daily").build()
            ).execute().body?.string() ?: return@withContext null

            // Step 2: Find any /novel/... link on the ranking page
            val novelUrl = Regex("""href=["']([^"']*/novel/[^"']+)["']""")
                .findAll(rankingHtml)
                .map {
                    if (it.groupValues[1].startsWith("http")) it.groupValues[1]
                    else "https://wtr-lab.com${it.groupValues[1]}"
                }
                .firstOrNull() ?: run {
                Log.w(TAG, "fetchKeyFromWtrLab: no novel link found on ranking page")
                return@withContext null
            }

            // Step 3: Navigate to chapter-1 — this page loads the Turbopack bundle with the key
            val chapterUrl = novelUrl.trimEnd('/') + "/chapter-1?service=webplus"
            Log.d(TAG, "fetchKeyFromWtrLab: loading chapter page: $chapterUrl")

            val chapterHtml = client.newCall(
                Request.Builder().url(chapterUrl).build()
            ).execute().body?.string() ?: return@withContext null

            // Step 4: Check inline first
            keyHeaderRegex.find(chapterHtml)?.groupValues?.get(1)?.let { key ->
                Log.d(TAG, "fetchKeyFromWtrLab: found key inline in chapter HTML")
                return@withContext key
            }

            // Step 5: Extract _next script URLs from the chapter page
            val scriptUrls = Regex("""<script[^>]+src=["']([^"']*/_next/[^"']+\.js[^"']*)["']""")
                .findAll(chapterHtml)
                .map { it.groupValues[1] }
                .map { if (it.startsWith("http")) it else "https://wtr-lab.com$it" }
                .distinct()
                .toList()

            Log.d(TAG, "fetchKeyFromWtrLab: found ${scriptUrls.size} _next scripts on chapter page")

            if (scriptUrls.isEmpty()) {
                Log.w(TAG, "fetchKeyFromWtrLab: no _next scripts on chapter page")
                return@withContext null
            }

            // Step 6: Load all scripts in parallel, return first key found
            searchKeyInScripts(scriptUrls)
        } catch (e: Exception) {
            Log.e(TAG, "fetchKeyFromWtrLab failed: ${e.message}")
            null
        }
    }

    /**
     * Loads a list of JS URLs in parallel and returns the first API key found.
     */
    private suspend fun searchKeyInScripts(urls: List<String>): String? = coroutineScope {
        Log.d(TAG, "searchKeyInScripts: searching ${urls.size} scripts (parallel)")

        val channel = Channel<String>(capacity = 1)

        val jobs = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    val js = client.newCall(
                        Request.Builder().url(url).build()
                    ).execute().body?.string() ?: return@async
                    val key = keyHeaderRegex.find(js)?.groupValues?.get(1) ?: return@async
                    Log.d(TAG, "searchKeyInScripts: found key in $url")
                    channel.trySend(key)
                } catch (e: Exception) {
                    Log.w(TAG, "searchKeyInScripts: failed $url: ${e.message}")
                }
            }
        }

        var result: String? = null
        while (result == null && jobs.any { it.isActive }) {
            result = channel.tryReceive().getOrNull()
            if (result == null) delay(50L)
        }
        if (result == null) result = channel.tryReceive().getOrNull()

        jobs.forEach { it.cancel() }
        channel.close()

        if (result == null) Log.w(TAG, "searchKeyInScripts: key not found in any script")
        result
    }

    // ─── Translation ────────────────────────────────────────────────────────────

    private suspend fun translateSingle(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val paragraphs = text.split("\n").filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return@withContext text
        try {
            translateChunks(paragraphs, sourceLanguage, targetLanguage).joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "translateSingle failed: ${e.message}")
            null
        }
    }

    /**
     * Mirrors wtr-lab website's v() function — unescapes HTML entities in translated text.
     */
    private fun unescapeHtmlEntities(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) {
                it.groupValues[1].toIntOrNull()
                    ?.toChar()
                    ?.toString()
                    ?: it.value
            }
    }

    /**
     * Splits paragraphs into chunks and translates each chunk.
     *
     * Mirrors wtr-lab website strategy exactly:
     *   - Join paragraphs with <br> instead of wrapping in <p> tags
     *   - Split translated result back on <br>
     *   - Unescape HTML entities via unescapeHtmlEntities() instead of Html.fromHtml()
     *   - No Html.fromHtml() — avoids stripping tags and paragraph misalignment
     */
    private suspend fun translateChunks(
        paragraphs: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        val maxChunkChars = 8_000
        val result = paragraphs.toMutableList()

        data class Chunk(val indices: List<Int>, val html: String)

        val chunks = mutableListOf<Chunk>()
        val currentIndices = mutableListOf<Int>()
        val currentParts = mutableListOf<String>()
        var currentLen = 0

        for ((i, para) in paragraphs.withIndex()) {
            // +4 accounts for "<br>" separator length
            if (currentLen > 0 && currentLen + para.length + 4 > maxChunkChars) {
                chunks.add(Chunk(currentIndices.toList(), currentParts.joinToString("<br>")))
                currentIndices.clear()
                currentParts.clear()
                currentLen = 0
            }
            currentIndices.add(i)
            currentParts.add(para)
            currentLen += para.length + 4
        }
        if (currentParts.isNotEmpty()) {
            chunks.add(Chunk(currentIndices.toList(), currentParts.joinToString("<br>")))
        }

        Log.d(TAG, "translateChunks: ${paragraphs.size} paragraphs → ${chunks.size} chunks, $sourceLang→$targetLang")

        val apiKey = getApiKey()

        for ((idx, chunk) in chunks.withIndex()) {
            if (idx > 0) delay(400L)

            val translated = try {
                translateHtml(chunk.html, sourceLang, targetLang, apiKey)
            } catch (e: Exception) {
                Log.e(TAG, "Chunk ${idx + 1}/${chunks.size} failed: ${e.message}, keeping original")
                continue
            }

            if (translated == chunk.html) continue

            // Mirror wtr-lab: replace <br> back to newline, split, unescape entities
            val translatedParas = translated
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .split("\n")
                .map { unescapeHtmlEntities(it.trim()) }
                .filter { it.isNotBlank() }

            val minSize = minOf(translatedParas.size, chunk.indices.size)
            for (pos in 0 until minSize) {
                result[chunk.indices[pos]] = translatedParas[pos]
            }

            if (translatedParas.size != chunk.indices.size) {
                Log.w(
                    TAG,
                    "Chunk ${idx + 1}: expected ${chunk.indices.size} paragraphs, got ${translatedParas.size}"
                )
            }
        }

        return result
    }

    private suspend fun translateHtml(
        html: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        val payload = listOf(listOf(html, sourceLang, targetLang), "wt_lib")
        val requestBody = Gson().toJson(payload)
            .toRequestBody("application/json+protobuf".toMediaType())

        val request = Request.Builder()
            .url(translateUrl)
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("Origin", "https://translate.google.com")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.body?.close()
            Log.e(TAG, "translateHtml: HTTP $code")
            return@withContext html
        }

        val body = response.body?.string() ?: return@withContext html
        try {
            val arr = JsonParser.parseString(body).asJsonArray
            arr.get(0).asJsonArray.get(0).asString
        } catch (e: Exception) {
            Log.e(TAG, "translateHtml: parse error — ${e.message}")
            html
        }
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        val boundaries = mutableListOf<IntRange>()
        val allParagraphs = mutableListOf<String>()
        for (text in texts) {
            val lines = text.split("\n").filter { it.isNotBlank() }
            val start = allParagraphs.size
            allParagraphs.addAll(lines)
            boundaries.add(start until start + lines.size)
        }

        val translatedAll = try {
            translateChunks(allParagraphs, sourceLanguage, targetLanguage)
        } catch (e: Exception) {
            Log.e(TAG, "translateBatch failed: ${e.message}")
            return@withContext emptyMap()
        }

        val result = mutableMapOf<String, String>()
        for ((i, text) in texts.withIndex()) {
            val range = boundaries[i]
            if (range.isEmpty()) {
                result[text] = text
                continue
            }
            val safeEnd = range.last.coerceAtMost(translatedAll.size - 1)
            if (safeEnd < range.first) {
                result[text] = text
                continue
            }
            val translatedLines = translatedAll.subList(range.first, safeEnd + 1)
            result[text] = if (translatedLines.isNotEmpty()) translatedLines.joinToString("\n") else text
        }

        Log.d(TAG, "translateBatch: total=${texts.size}, translated=${result.size}")
        result
    }

    override fun downloadModel(language: String) {}
    override fun removeModel(language: String) {}

    companion object {
        private const val TAG = "TranslationGooglePA"
    }
}