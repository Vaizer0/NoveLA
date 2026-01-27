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
import my.noveldokusha.scraper.utils.buildUrl
import org.jsoup.nodes.Document

class NovelFull(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "novelfull"
    override val nameStrId = R.string.source_name_novelfull
    override val baseUrl = "https://novelfull.net/"
    override val catalogUrl = "https://novelfull.net/latest-release-novel"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelfull.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Catalog selectors
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

        // Book selectors
        book = BookSelectors(
            title = text("h3.title").Clean(),
            cover = attr("src", ".book img[src]"),
            description = text(".desc-text")
        ),

        // Chapters selectors
        chapters = ChapterSelectors(
            list = elements("ul.list-chapter li a"),
            title = attr("title", "a"),
            content = text("#chapter-content")
                .removeElementsDOM("script", ".ads")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Chapters pagination - PAGE_BASED for NovelFull
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
        buildCatalogUrl = { index -> Companion.buildCatalogUrl(baseUrl, index) },
        buildSearchUrl = { index, query -> Companion.buildSearchUrl(baseUrl, index, query) },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.novelBinCoverUrl()
    )

    companion object {
        fun buildCatalogUrl(baseUrl: String, index: Int): String {
            val page = index + 1
            return if (page == 1) {
                buildUrl(baseUrl, "latest-release-novel")
            } else {
                buildUrl(baseUrl, "latest-release-novel?page=$page")
            }
        }

        fun buildSearchUrl(baseUrl: String, index: Int, input: String): String {
            val page = index + 1
            val base = buildUrl(baseUrl, "search")
            val encodedInput = java.net.URLEncoder.encode(input, "UTF-8")
            val params = "keyword=$encodedInput" + if (page > 1) "&page=$page" else ""
            return "$base?$params"
        }

        fun transformBookUrl(baseUrl: String, url: String): String = when {
            url.startsWith("http") -> url // Already absolute
            url.startsWith("//") -> "https:$url" // Protocol-relative
            else -> buildUrl(baseUrl, url) // Relative - add base URL
        }

        fun transformChapterUrl(baseUrl: String, href: String): String =
            java.net.URI(baseUrl).resolve(href).toString()
    }
}
