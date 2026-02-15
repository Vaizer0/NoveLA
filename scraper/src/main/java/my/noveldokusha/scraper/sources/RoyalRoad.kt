package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers

/**
 * RoyalRoad.com scraper using the new unified architecture
 */
class RoyalRoad(
    private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: org.jsoup.nodes.Document) = getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    override val id = "royal_road"
    override val nameStrId = R.string.source_name_royal_road
    override val baseUrl = "https://www.royalroad.com"
    override val catalogUrl = "https://www.royalroad.com/fictions/latest-updates?page=1"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/royalroad.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".fiction-list-item"),
            title = text("h2 a").Clean(),
            url = attr("href", "h2 a"),
            cover = attr("src", "img")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements(".fiction-list-item"),
            title = text("h2 a").Clean(),
            url = attr("href", "h2 a"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1.font-white").Clean(),
            cover = attr("src", ".cover-art-container img[src]"),
            description = text(".description"),
            latestChapterHash = text(".portlet-title .actions .label").Clean()
        ),

        chapters = ChapterSelectors(
            list = elements("tr.chapter-row td:first-child a[href]"),
            title = text("a"),
            content = text(".chapter-content")
                .removeElementsDOM("script", "a", ".ads-title")
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
            if (index == 0) "$baseUrl/fictions/best-rated"
            else "$baseUrl/fictions/best-rated?page=${index + 1}"
        },
        buildSearchUrl = { index, query ->
            "$baseUrl/fictions/search?title=$query&page=${index + 1}"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )

    // Note: URL transformers are now inline in HtmlSelectors config
}
