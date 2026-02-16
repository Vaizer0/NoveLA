package my.noveldokusha.scraper.templates

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postPayload
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toJson
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import timber.log.Timber
import org.jsoup.nodes.Document
import kotlin.random.Random

/**
 * Abstract WTR-Lab scraper template with configurable translation language.
 * Extend this class to create versions for different translation languages.
 */
abstract class WtrLabScraperTemplate(
    protected val networkClient: NetworkClient
) : SourceInterface.Catalog {

    // Abstract properties to override for each language version
    abstract override val id: String
    abstract override val nameStrId: Int
    abstract override val language: LanguageCode
    
    // Translation language code (en, es, ru, etc.) - passed to API
    protected abstract val translationLanguage: String
    
    // Base URL for API
    abstract override val baseUrl: String
    
    // Catalog URL - uses translation language
    override val catalogUrl: String by lazy { "$baseUrl/novel-list" }

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        try {
            val chapterUrl = doc.location()
            val parts = chapterUrl.split("/")
            val novelIndex = parts.indexOf("novel")
            val originalLanguage = parts.getOrNull(novelIndex - 1) ?: "en"
            val novelId = parts.getOrNull(novelIndex + 1) ?: return@withContext ""
            val chapterPart = parts.lastOrNull() ?: return@withContext ""
            val chapterNo = chapterPart.removePrefix("chapter-").toIntOrNull() ?: 1

            val apiUrl = "$baseUrl/api/reader/get"

            // Use translation language from subclass
            val formData = mapOf(
                "translate" to "ai",
                "language" to translationLanguage,
                "raw_id" to novelId,
                "chapter_no" to chapterNo.toString(),
                "retry" to "false",
                "force_retry" to "false"
            )

            // Build POST request using postRequest
            val requestBuilder = postRequest(apiUrl).postPayload {
                formData.forEach { (key, value) -> add(key, value) }
            }

            // Make the request through networkClient to enable Cloudflare bypass
            val response = networkClient.call(requestBuilder)
            val json = response.toJson() as JsonObject

            if (json.has("requireTurnstile")) {
                Timber.w("WtrLab: Chapter requires Turnstile verification, returning chapter URL for WebView bypass")
                // Return chapter URL so reader can open it in WebView for CF bypass
                return@withContext chapterUrl
            }

            // Get data from JSON
            val data = json.getAsJsonObject("data")?.getAsJsonObject("data")
                ?: json.getAsJsonObject("data")
                ?: json

            // Build glossary terms map (index -> translated term)
            val glossaryTerms = mutableMapOf<Int, String>()
            val glossaryData = data?.getAsJsonObject("glossary_data")
            val termsArray = glossaryData?.getAsJsonArray("terms")
            if (termsArray != null) {
                for (i in 0 until termsArray.size()) {
                    val termEntry = termsArray.get(i).asJsonArray
                    if (termEntry.size() >= 2) {
                        val translatedTerm = termEntry.get(0)?.asString
                        if (!translatedTerm.isNullOrEmpty()) {
                            glossaryTerms[i] = translatedTerm
                        }
                    }
                }
            }

            // Get patch array (zh -> en replacements)
            val patchArray = data?.getAsJsonArray("patch")
            val patchMap = mutableMapOf<String, String>()
            if (patchArray != null) {
                for (i in 0 until patchArray.size()) {
                    val patch = patchArray.get(i).asJsonObject
                    val zh = patch.get("zh")?.asString
                    val en = patch.get("en")?.asString
                    if (!zh.isNullOrEmpty() && !en.isNullOrEmpty()) {
                        patchMap[zh] = en
                    }
                }
            }

            // Parse chapter content with term replacements
            val content = StringBuilder()
            val body = data?.getAsJsonArray("body")
            
            if (body != null) {
                for (i in 0 until body.size()) {
                    val element = body.get(i)
                    if (element.isJsonPrimitive) {
                        var text = element.asString
                        
                        // Skip image placeholders
                        if (text == "[image]" || text.isBlank()) {
                            continue
                        }
                        
                        // Replace glossary placeholders: ※0⛬ and ※0〓
                        for ((index, term) in glossaryTerms) {
                            text = text.replace("※$index⛬", term)
                            text = text.replace("※$index〓", term)
                        }
                        
                        // Apply patch replacements (zh -> en)
                        for ((zh, en) in patchMap) {
                            text = text.replace(zh, en)
                        }
                        
                        if (text.isNotBlank()) {
                            content.append("<p>$text</p>\n")
                        }
                    }
                }
            }

            content.toString()
        } catch (e: Exception) {
            Timber.e(e, "WtrLab: Error fetching chapter text")
            ""
        }
    }

    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/wtr-lab.png"
    override val iconResId = null

    private val config: HtmlSelectors by lazy {
        HtmlSelectors(
            baseUrl = baseUrl,
            language = language,

            catalog = CatalogSelectors(
                item = elements("div.serie-item"),
                title = text("a.title").Clean(),
                url = attr("href", "a.title"),
                cover = attr("src", ".image-wrap img")
            ),

            search = SearchSelectors(
                item = elements("div.serie-item"),
                title = text("a.title").Clean(),
                url = attr("href", "a.title"),
                cover = attr("src", ".image-wrap img")
            ),

            book = BookSelectors(
                title = text("h1.long-title").Clean(),
                cover = attr("src", ".image-section .image-wrap img"),
                description = text(".desc-wrap .description"),
                latestChapterHash = text(".detail-line:contains('Chapters')").Clean()

            ),

            chapters = ChapterSelectors(
                list = elements("a[href*='/chapter-']"),
                title = text("a"),
                content = text("article, .content, main")
                    .removeElementsDOM("script", "nav", "footer", ".ads", ".advertisement", ".comments")
                    .applyStandardContentTransforms(baseUrl)
            ),

            chapterPaginationType = ChapterPaginationType.AJAX_BASED,
            ajaxChapterListProvider = { bookUrl, networkClient ->
                runCatching {
                    // Delay to avoid rate limiting
                    delay(Random.nextLong(200, 500))
                    
                    // Extract novelId from URL: /{lang}/novel/{id}/{slug}
                    val novelId = Regex("/novel/(\\d+)/").find(bookUrl)?.groupValues?.get(1)
                        ?: return@runCatching emptyList()

                    // Get chapters from API
                    val chaptersUrl = "$baseUrl/api/chapters/$novelId"
                    val chaptersJson = networkClient.call(my.noveldokusha.network.getRequest(chaptersUrl)).toJson() as JsonObject
                    val chaptersArray = chaptersJson.getAsJsonArray("chapters") 
                        ?: return@runCatching emptyList()

                    // Get slug from URL for building chapter URLs
                    val slug = Regex("/novel/\\d+/(.+?)(?:/|$)").find(bookUrl)?.groupValues?.get(1) ?: ""

                    val result = mutableListOf<ChapterResult>()
                    for (i in 0 until chaptersArray.size()) {
                        val chapter = chaptersArray.get(i).asJsonObject
                        val order = chapter.get("order")?.asInt ?: return@runCatching emptyList()
                        val title = chapter.get("title")?.asString ?: "Chapter $order"
                        val chapterUrl = "$baseUrl/novel/$novelId/$slug/chapter-$order"
                        
                        result.add(ChapterResult(
                            title = "$order: $title",
                            url = chapterUrl
                        ))
                    }
                    result
                }.onFailure { e ->
                    Timber.e(e, "WtrLab: Failed to get chapters for $bookUrl")
                }.getOrNull() ?: emptyList()
            },

            buildCatalogUrl = { index ->
                val page = index + 1
                "$catalogUrl?page=$page"
            },

            buildSearchUrl = { index, query ->
                val page = index + 1
                "$baseUrl/novel-finder?text=$query&page=$page"
            },

            transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
            transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
            transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
        )
    }
}
