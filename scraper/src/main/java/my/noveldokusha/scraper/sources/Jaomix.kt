package my.noveldokusha.scraper.sources

import kotlinx.coroutines.delay
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.POST
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document
import kotlin.random.Random

class Jaomix(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // Методы интерфейса (ОБЯЗАТЕЛЬНО!)
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    // Идентификаторы источника
    override val id = "jaomix"
    override val nameStrId = R.string.source_name_jaomix
    override val baseUrl = "https://jaomix.ru/"
    override val catalogUrl = "$baseUrl"
    override val language = LanguageCode.RUSSIAN
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/jaomix.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements("div.block-home > div.one"),
            title = text("div.title-home").Clean(),
            url = attr("href", "div.img-home > a"),
            cover = attr("src", "div.img-home > a > img")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements("div.block-home > div.one"),
            title = text("div.title-home").Clean(),
            url = attr("href", "div.img-home > a"),
            cover = attr("src", "div.img-home > a > img")
        ),

        book = BookSelectors(
            title = text("h1").Clean(),
            cover = attr("src", "div.img-book > img"),
            description = text("#desc-tab")
        ),

        chapters = ChapterSelectors(
            list = elements("div.title a"),
            title = text("h2"),
            content = text(".entry-content")
                .removeElementsDOM("script", ".ads", ".adblock-service", ".lazyblock", ".clear", "style")
                .applyStandardContentTransforms(baseUrl)

        ),

        // Chapters with AJAX pagination
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, networkClient ->
            // Load book page to get total pages count from select element
            val bookDoc = networkClient.get(bookUrl).toDocument()
            val maxPage = bookDoc.selectFirst("select.sel-toc")?.select("option")?.size
                ?: bookDoc.selectFirst("select[onchange*='loadChaptList']")?.select("option")?.size
                ?: 10 // fallback if select not found
            
            val ajaxUrl = buildUrl(baseUrl, "wp-admin/admin-ajax.php")
            val allChapters = mutableListOf<ChapterResult>()
            
            // Load all chapters page by page via AJAX (from last page to first for correct order)
            for (page in maxPage downTo 1) {
                val ajaxDoc = POST(
                    url = ajaxUrl,
                    data = mapOf(
                        "action" to "loadpagenavchapstt",
                        "page" to page.toString()
                    ),
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UQ1A.240205.004) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.83 Mobile Safari/537.36",
                        "Accept" to "text/html, */*; q=0.01",
                        "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to baseUrl.removeSuffix("/"),
                        "Referer" to bookUrl,
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin"
                    ),
                    networkClient = networkClient
                )
                
                val chapters = ajaxDoc.select("div.title a[href]").map { element ->
                    val titleEl = element.selectFirst("h2")
                    val title = titleEl?.text()?.trim() ?: element.text().trim()
                    val url = element.attr("href")
                    ChapterResult(
                        title = title,
                        url = url
                    )
                }.reversed() // Reverse chapters within each page to get oldest first
                
                if (chapters.isEmpty()) break
                allChapters.addAll(chapters)
                
                // Random delay to avoid rate limiting (200-350ms)
                delay(Random.nextLong(150, 351))
            }
            
            allChapters
        },

        // Chapters order - already in correct order (oldest first)
        reverseChapters = false,
        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            if (index == 0) catalogUrl
            else "$catalogUrl?gpage=${index + 1}"
        },
        buildSearchUrl = { index, query ->
            val baseSearchUrl = "$baseUrl?searchrn=$query"
            if (index == 0) baseSearchUrl
            else "$baseSearchUrl&gpage=${index + 1}"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.jaomixCoverUrl()
    )

    // Note: URL transformers are now inline in HtmlSelectors config
}
