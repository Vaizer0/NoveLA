package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

class AllNovel(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "allnovel"
    override val nameStrId = R.string.source_name_allnovel
    override val baseUrl = "https://allnovel.org/"
    override val catalogUrl = "https://allnovel.org/latest-release-novel"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/allnovel.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".col-truyen-main .row"),
            title = text("div.col-xs-7 > div > h3 > a").Clean(),
            url = attr("href", "div.col-xs-7 > div > h3 > a"),
            cover = attr("src", "div.col-xs-3 > div > img")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements(".col-truyen-main .row"),
            title = text("div.col-xs-7 > div > h3 > a").Clean(),
            url = attr("href", "div.col-xs-7 > div > h3 > a"),
            cover = attr("src", "div.col-xs-3 > div > img")
        ),

        book = BookSelectors(
            title = text("h3.title").Clean(),
            cover = attr("src", ".book img[src]"),
            description = text(".desc-text")
        ),

        chapters = ChapterSelectors(
            list = elements("ul.list-chapter li a"),
            title = text(".chapter-text"),
            content = text("#chapter-content")
                .removeElementsDOM("script", ".ads", "h3")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Chapters pagination - PAGE_BASED
        chapterPaginationType = ChapterPaginationType.PAGE_BASED,
        chapterPaginationConfig = ChapterPaginationConfig(
            maxPageExtractor = { doc ->
                val lastPageElement = doc.selectFirst("#list-chapter > ul:nth-child(3) > li.last > a")
                val href = lastPageElement?.attr("href")
                href?.substringAfter("?page=", "")?.toIntOrNull() ?: 1
            },
            pageUrlBuilder = { bookUrl, page -> "$bookUrl?page=$page" },
            chapterSelector = "ul.list-chapter li a"
        ),

        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            if (page == 1) {
                buildUrl(baseUrl, "latest-release-novel")
            } else {
                buildUrl(baseUrl, "latest-release-novel?page=$page")
            }
        },
        buildSearchUrl = { index, query ->
            val page = index + 1
            val builder = buildUrl(baseUrl, "search").toUrlBuilderSafe()
            builder.add("keyword", query)
            if (page > 1) builder.add("page", page.toString())
            builder.toString()
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.novelBinCoverUrl() // Special NovelBin-style cover transformer
    )

}
