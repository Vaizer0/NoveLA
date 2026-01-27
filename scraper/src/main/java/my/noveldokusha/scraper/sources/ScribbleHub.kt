package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.POST
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import org.jsoup.nodes.Document

class ScribbleHub(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "scribblehub"
    override val nameStrId = R.string.source_name_scribblehub
    override val baseUrl = "https://www.scribblehub.com/"
    override val catalogUrl = "https://www.scribblehub.com/series-ranking/?sort=1&order=2"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/scribblehub.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".search_main_box"),
            title = text(".search_title a").Clean(),
            url = attr("href", ".search_title a"),
            cover = attr("src", ".search_img img")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements(".search_main_box"),
            title = text(".search_title a").Clean(),
            url = attr("href", ".search_title a"),
            cover = attr("src", ".search_img img")
        ),

        book = BookSelectors(
            title = text("div.fic_title").Clean(),
            cover = attr("src", ".fic_image img[src], .novel-cover img"),
            description = text(".wi_fic_desc")
        ),

        chapters = ChapterSelectors(
            list = elements(".toc_w a[href]"),
            title = text("a"),
            content = text("#chp_raw")
                .removeElementsDOM("script", ".modern_chapter_ad", "div.modern_chapter_ad")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Chapters pagination - AJAX based
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, networkClient ->
            // Extract series ID from URL
            val seriesId = Regex("series/(\\d+)/").find(bookUrl)?.groupValues?.get(1)
                ?: throw Exception("Invalid ScribbleHub book URL: $bookUrl")

            // AJAX URL for chapter list
            val ajaxUrl = buildUrl(baseUrl, "wp-admin/admin-ajax.php")

            // POST request with form data and AJAX headers
            val doc = POST(
                url = ajaxUrl,
                data = mapOf(
                    "action" to "wi_getreleases_pagination",
                    "pagenum" to "-1", // Get all chapters
                    "mypostid" to seriesId
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to baseUrl.removeSuffix("/"),
                    "Referer" to bookUrl,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                networkClient = networkClient
            )

            // Parse chapters from AJAX response
            doc.select(".toc_w a[href]").reversed().map { element: org.jsoup.nodes.Element ->
                ChapterResult(
                    title = element.text(),
                    url = element.attr("href")
                )
            }
        },

        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            "$baseUrl/series-ranking/?sort=1&order=2&pg=$page"
        },
        buildSearchUrl = { index, query ->
            val page = index + 1
            "$baseUrl?s=$query&post_type=fictionposts&paged=$page"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}
