package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

class NovelHall(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // Методы интерфейса (ОБЯЗАТЕЛЬНО!)
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    // Идентификаторы источника
    override val id = "novelhall"
    override val nameStrId = R.string.source_name_novelhall
    override val baseUrl = "https://www.novelhall.com"
    override val catalogUrl = "https://www.novelhall.com/lastupdate.html"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelhall.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements("table tbody tr"),
            title = text("td.w70 a[href]").Clean(),
            url = attr("href", "td.w70 a[href]"),
            cover = attr("src", ":not(*)") // No cover selector
        ),

        // Search selectors (different from catalog)
        search = SearchSelectors(
            item = elements("td:nth-child(2) a[href]"),
            title = text("a[href]").Clean(),
            url = attr("href", "a[href]"),
            cover = attr("src", ":not(*)") // No cover selector
        ),

        book = BookSelectors(
            title = text("h1").Clean(),
            cover = attr("src", ".book-img.hidden-xs img[src]"),
            description = text("span.js-close-wrap")
        ),

        chapters = ChapterSelectors(
            list = elements("#morelist a[href]"),
            title = text("a"),
            content = text("div#htmlContent")
                .removeElementsDOM("script")
                .applyStandardContentTransforms(baseUrl)
        ),


        // Chapters without pagination
        chapterPaginationType = ChapterPaginationType.NONE,

        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            if (page == 1) "$baseUrl/completed.html"
            else "$baseUrl/completed-$page.html"
        },
        buildSearchUrl = { index, query ->
            if (index == 0) "$baseUrl/index.php?s=so&module=book&keyword=$query"
            else "$baseUrl/index.php?s=so&module=book&keyword=$query&page=${index + 1}"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )

    // Note: URL transformers are now inline in HtmlSelectors config
}
