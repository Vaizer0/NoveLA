package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import timber.log.Timber
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import my.noveldokusha.network.toDocument

class Twkan(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)
    override val id = "twkan"
    override val nameStrId = R.string.source_name_twkan
    override val baseUrl = "https://twkan.com/"
    override val catalogUrl = "https://twkan.com/novels/newhot_2_0_1.html"
    override val language = LanguageCode.CHINESE
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/twkan.png"
    override val iconResId = null

    // Declarative configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements("#article_list_content li"),
            title = text("h3 a").Clean(),
            url = attr("href", "h3 a"),
            cover = attr("data-src", "img")
        ),

        // Search selectors
        search = SearchSelectors(
            item = elements("#article_list_content li, .search-result li, .book-list li, .novel-list li, li:has(a[href*='/book/'])"),
            title = text("h3 a, h3").Clean(),
            url = attr("href", "a[href*=/book/]"),
            cover = attr("data-src", "img")
        ),

        // Book selectors
        book = BookSelectors(
            cover = attr("src", ".bookimg2 img"),
            description = text("#tab_info .navtxt p")
        ),

        // Chapters selectors - will be overridden by AJAX
        chapters = ChapterSelectors(
            list = elements(""), // Not used due to AJAX
            title = text("a"),
            content = text("#txtcontent0")
                .removeElementsDOM("script", ".txtad")
                .applyStandardContentTransforms(baseUrl)
        ),


        // Chapters pagination - AJAX based
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
            try {
                // Extract book ID from /book/{id}.html
                val bookId = bookUrl.substringAfter("/book/").substringBefore(".html")

                // Use AJAX endpoint for full chapter list
                val ajaxUrl = "https://twkan.com/ajax_novels/chapterlist/$bookId.html"

                networkClient.get(ajaxUrl).toDocument()
                    .select("ul li a[href]")
                    .map { element ->
                        ChapterResult(
                            title = element.text().trim(),
                            url = element.attr("abs:href")
                        )
                    }
            } catch (e: Exception) {
                Timber.w("Failed to load chapters for Twkan: ${e.message}")
                emptyList()
            }
        },

        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            if (page == 1) catalogUrl
            else "https://twkan.com/novels/newhot_2_0_${page}.html"
        },

        buildSearchUrl = { index, query ->
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val page = index + 1
            "https://twkan.com/search/$encodedQuery/$page.html"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )

}
