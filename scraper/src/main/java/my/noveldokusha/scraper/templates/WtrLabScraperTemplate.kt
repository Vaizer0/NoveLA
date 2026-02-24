package my.noveldokusha.scraper.templates

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toJson
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import timber.log.Timber
import kotlin.random.Random

abstract class WtrLabScraperTemplate(
    protected val networkClient: NetworkClient,
    protected val appPreferences: AppPreferences
) : SourceInterface.Catalog {

    override val id = "wtrlab"
    override val nameStrId = R.string.source_name_wtrlab
    override val language = LanguageCode.MULTILANGUAGE
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/wtr-lab.png"
    override val iconResId = null
    override val baseUrl = "https://wtr-lab.com/"
    override val catalogUrl: String get() = "${baseUrl}novel-list"

    private val translationMode: String get() = appPreferences.WTR_LAB_MODE.value
    private val translationLanguage: String get() = appPreferences.WTR_LAB_LANGUAGE.value

    private fun apiTranslateParam(): String = if (translationMode == "raw") "web" else "ai"
    private fun sourceLanguageForGT(): String = if (translationMode == "raw") "zh-CN" else "en"

    private fun getTargetLangCode(lang: String): String = when (lang) {
        "en" -> "en"; "es" -> "es"; "ru" -> "ru"; "de" -> "de"
        "pl" -> "pl"; "it" -> "it"; "fr" -> "fr"
        "id" -> "id"; "tr" -> "tr"
        else -> "none"
    }

    private val translator by lazy {
        WtrLabTranslator(networkClient, ::sourceLanguageForGT, baseUrl)
    }

    private fun cleanApiParagraph(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .replace(Regex("(?i)\\A[\\s\\p{Z}\\uFEFF]*((Глава\\s+\\d+|Chapter\\s+\\d+)[^\\n\\r]*[\\n\\r\\s]*)+"), "")
            .replace(Regex("(?im)^\\s*(Translator|Editor|Proofreader|Read\\s+(at|on|latest))[:\\s][^\\n\\r]{0,70}(\\r?\\n|\$)"), "")
            .trim()

    // ── Chapter fetching ──────────────────────────────────────────────────────

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val chapterUrl = doc.location()
        Timber.d("WtrLab: getChapterText for $chapterUrl")

        try {
            val parts = chapterUrl.trimEnd('/').split("/")
            val novelIndex = parts.indexOf("novel")
            if (novelIndex == -1) {
                Timber.e("WtrLab: 'novel' not found in URL: $chapterUrl")
                return@withContext ""
            }
            val novelId = parts.getOrNull(novelIndex + 1) ?: run {
                Timber.e("WtrLab: No novelId in URL: $chapterUrl")
                return@withContext ""
            }
            val chapterPart = parts.lastOrNull { it.startsWith("chapter-") } ?: parts.last()
            val chapterNo = chapterPart.removePrefix("chapter-").toIntOrNull() ?: 1

            val lang = translationLanguage
            Timber.d("WtrLab: novelId=$novelId, chapterNo=$chapterNo, translate=${apiTranslateParam()}, lang=$lang")

            val apiResponse = fetchChapterFromApi(chapterUrl, novelId, chapterNo, lang)
                ?: return@withContext ""

            val paragraphs = buildParagraphs(apiResponse)

            if (paragraphs.isEmpty()) {
                Timber.w("WtrLab: 0 paragraphs parsed")
                return@withContext ""
            }

            val targetLang = getTargetLangCode(lang)
            val finalParagraphs = if (targetLang != "none") {
                try {
                    translator.translateChunks(paragraphs, targetLang)
                } catch (e: Exception) {
                    Timber.e(e, "WtrLab: GT failed, using original")
                    paragraphs
                }
            } else paragraphs

            finalParagraphs.joinToString("\n") { "<p>$it</p>" }

        } catch (e: Exception) {
            Timber.e(e, "WtrLab: Unexpected error")
            throw e
        }
    }

    private data class ChapterApiData(
        val bodyArray: com.google.gson.JsonArray?,
        val resolvedBody: String,
        val glossaryTerms: Map<Int, String>,
        val patchMap: Map<String, String>
    )

    private suspend fun fetchChapterFromApi(
        chapterUrl: String,
        novelId: String,
        chapterNo: Int,
        lang: String
    ): ChapterApiData? {
        val jsonBody = Gson().toJson(
            mapOf(
                "translate"   to apiTranslateParam(),
                "language"    to lang,
                "raw_id"      to novelId,
                "chapter_no"  to chapterNo,
                "retry"       to false,
                "force_retry" to false,
            )
        )
        val request = Request.Builder()
            .url("${baseUrl}api/reader/get")
            .addHeader("Referer", chapterUrl)
            .addHeader("Origin", baseUrl.trimEnd('/'))
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))

        val responseStr = networkClient.call(request).body?.string() ?: run {
            Timber.e("WtrLab: Empty response body")
            return null
        }
        Timber.d("WtrLab: API response (first 300): ${responseStr.take(300)}")

        val json = try {
            JsonParser.parseString(responseStr).asJsonObject
        } catch (e: Exception) {
            Timber.e(e, "WtrLab: Response is not JSON")
            return null
        }

        if (json.has("requireTurnstile") || json.has("turnstile")) {
            Timber.w("WtrLab: Turnstile required — returning URL for WebView")
            throw Exception(chapterUrl)
        }

        if (json.get("success")?.asBoolean == false) {
            val errorCode = json.get("code")?.asInt
            val errorMsg = json.get("error")?.asString ?: "Unknown API error"
            Timber.e("WtrLab: API error [$errorCode]: $errorMsg")
            throw Exception("[$errorCode] $errorMsg")
        }

        val outerData = json.getAsJsonObject("data")
        val data: JsonObject = outerData?.getAsJsonObject("data") ?: outerData ?: run {
            Timber.e("WtrLab: No 'data' in response")
            return null
        }

        val bodyElement = data.get("body")
        val rawBody = when {
            bodyElement == null         -> null
            bodyElement.isJsonArray     -> bodyElement.asJsonArray.toString()
            bodyElement.isJsonPrimitive -> bodyElement.asString
            else                        -> null
        } ?: run {
            Timber.e("WtrLab: Body is empty")
            return null
        }

        val resolvedBody = decryptBodyIfNeeded(rawBody)

        val bodyArray = try {
            if (resolvedBody.startsWith("[")) JsonParser.parseString(resolvedBody).asJsonArray
            else null
        } catch (e: Exception) { null }

        val glossaryTerms = buildMap<Int, String> {
            data.getAsJsonObject("glossary_data")
                ?.getAsJsonArray("terms")
                ?.forEachIndexed { i, el ->
                    runCatching {
                        el.asJsonArray.get(0)?.asString?.takeIf { it.isNotEmpty() }
                            ?.let { put(i, it) }
                    }
                }
        }

        val patchMap = buildMap<String, String> {
            data.getAsJsonArray("patch")?.forEach { el ->
                runCatching {
                    val obj = el.asJsonObject
                    val zh = obj.get("zh")?.asString
                    val en = obj.get("en")?.asString
                    if (!zh.isNullOrEmpty() && !en.isNullOrEmpty()) put(zh, en)
                }
            }
        }

        return ChapterApiData(bodyArray, resolvedBody, glossaryTerms, patchMap)
    }

    private suspend fun decryptBodyIfNeeded(rawBody: String): String {
        if (!rawBody.startsWith("arr:")) return rawBody

        Timber.d("WtrLab: Body encrypted, sending to proxy")
        return try {
            val proxyRequest = Request.Builder()
                .url("https://wtr-lab-proxy.fly.dev/chapter")
                .addHeader("Content-Type", "application/json")
                .post(
                    Gson().toJson(mapOf("payload" to rawBody))
                        .toRequestBody("application/json".toMediaType())
                )
            val proxyStr = networkClient.call(proxyRequest).body?.string() ?: ""
            Timber.d("WtrLab: Proxy response (first 100): ${proxyStr.take(100)}")

            val proxyElement = JsonParser.parseString(proxyStr)
            when {
                proxyElement.isJsonArray  -> proxyElement.asJsonArray.toString()
                proxyElement.isJsonObject -> proxyElement.asJsonObject.get("body")
                    ?.asJsonArray?.toString() ?: rawBody
                else -> rawBody
            }
        } catch (e: Exception) {
            Timber.e(e, "WtrLab: Proxy decryption failed")
            rawBody
        }
    }

    private fun buildParagraphs(data: ChapterApiData): List<String> {
        val paragraphs = mutableListOf<String>()
        if (data.bodyArray != null) {
            for (i in 0 until data.bodyArray.size()) {
                val el = data.bodyArray.get(i)
                if (!el.isJsonPrimitive) continue
                var text = cleanApiParagraph(el.asString)
                if (text == "[image]" || text.isBlank()) continue
                for ((idx, term) in data.glossaryTerms) {
                    text = text.replace("※$idx⛬", term).replace("※$idx〓", term)
                }
                for ((zh, en) in data.patchMap) text = text.replace(zh, en)
                if (text.isNotBlank()) paragraphs.add(text)
            }
        } else {
            data.resolvedBody.split("\n")
                .filter { it.isNotBlank() }
                .forEach { paragraphs.add(it) }
        }
        return paragraphs
    }

    // ── Catalog / Book delegates ──────────────────────────────────────────────

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    // ── Config ────────────────────────────────────────────────────────────────

    private val config: HtmlSelectors by lazy {
        HtmlSelectors(
            baseUrl  = baseUrl,
            language = language,

            catalog = CatalogSelectors(
                item  = elements("div.serie-item"),
                title = text("a.title").Clean(),
                url   = attr("href", "a.title"),
                cover = attr("src", ".image-wrap img")
            ),

            search = SearchSelectors(
                item  = elements("div.serie-item"),
                title = text("a.title").Clean(),
                url   = attr("href", "a.title"),
                cover = attr("src", ".image-wrap img")
            ),

            book = BookSelectors(
                title             = text("h1.long-title").Clean(),
                cover             = attr("src", ".image-section .image-wrap img"),
                description       = text(".desc-wrap .description"),
                latestChapterHash = text(".detail-line:contains('Chapters')").Clean()
            ),

            chapters = ChapterSelectors(
                list  = elements("a[href*='/chapter-']"),
                title = text("a"),
                content = text("article, .content, main")
            ),

            chapterPaginationType = ChapterPaginationType.AJAX_BASED,

            ajaxChapterListProvider = { bookUrl, networkClient ->
                runCatching {
                    delay(Random.nextLong(200, 500))

                    val novelId = Regex("/novel/(\\d+)/").find(bookUrl)?.groupValues?.get(1)
                        ?: return@runCatching emptyList()
                    val slug = Regex("/novel/\\d+/([^/]+)").find(bookUrl)?.groupValues?.get(1) ?: ""

                    val chaptersJson = networkClient.call(
                        Request.Builder()
                            .url("${baseUrl}api/chapters/$novelId")
                            .addHeader("Referer", bookUrl)
                            .get()
                    ).toJson() as? JsonObject ?: return@runCatching emptyList()

                    val chaptersArray = chaptersJson.getAsJsonArray("chapters")
                        ?: return@runCatching emptyList()

                    (0 until chaptersArray.size()).mapNotNull { i ->
                        val chapter = chaptersArray.get(i).asJsonObject
                        val order = chapter.get("order")?.asInt ?: return@mapNotNull null
                        val title = chapter.get("title")?.asString ?: "Chapter $order"
                        ChapterResult(
                            title = "$order: $title",
                            url   = "${baseUrl}novel/$novelId/$slug/chapter-$order"
                        )
                    }
                }.onFailure { e ->
                    Timber.e(e, "WtrLab: Failed to load chapters for $bookUrl")
                }.getOrNull() ?: emptyList()
            },

            buildCatalogUrl = { index -> "$catalogUrl?page=${index + 1}" },
            buildSearchUrl  = { index, query -> "${baseUrl}novel-finder?text=$query&page=${index + 1}" },

            transformBookUrl    = UrlTransformers.standardBookUrl(baseUrl),
            transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
            transformCoverUrl   = UrlTransformers.standardCoverUrl(baseUrl)
        )
    }
}