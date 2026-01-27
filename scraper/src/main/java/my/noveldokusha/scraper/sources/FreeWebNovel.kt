package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.configs.HtmlSelectors
import org.jsoup.nodes.Document

class FreeWebNovel(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "freewebnovel"
    override val nameStrId = R.string.source_name_freewebnovel
    override val baseUrl = "https://freewebnovel.com"
    override val catalogUrl = "https://freewebnovel.com/completed-novel/"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/freewebnovel.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements(".ul-list1 .li-row"),
            title = text(".tit a").Clean(),
            url = attr("href", ".tit a"),
            cover = attr("src", ".pic img")
        ),

        search = SearchSelectors(
            item = elements(".serach-result .li-row, .ul-list1 .li-row"),
            title = text(".tit a").Clean(),
            url = attr("href", ".tit a"),
            cover = attr("src", ".pic img")
        ),

        book = BookSelectors(
            title = text("h1.tit").Clean(),
            cover = attr("src", ".pic img"),
            description = text(".m-desc .txt")
        ),

        chapters = ChapterSelectors(
            list = elements("#idData li a"),
            title = attr("title", "a.con"),
            content = text("div.txt")
                .removeElementsDOM("script", ".ads", ".advertisement", "h4", "sub")
                .applyStandardContentTransforms(baseUrl)

        ),

        chapterPaginationType = ChapterPaginationType.NONE,
        chapterPaginationConfig = null,

        postSearchEnabled = true,
        postSearchUrl = "$baseUrl/search",
        postSearchDataBuilder = { query -> mapOf("searchkey" to query) },
        searchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0",
            "Referer" to "https://freewebnovel.com/"
        ),

        buildCatalogUrl = { index ->
            val page = index + 1
            "$baseUrl/completed-novel/$page"
        },
        buildSearchUrl = { index, query ->
            "$baseUrl/search"
        },

        transformBookUrl = { url ->
            when {
                url.startsWith("http") -> url // Already absolute
                url.startsWith("//") -> "https:$url" // Protocol-relative
                else -> buildUrl(baseUrl, url) // Relative - add base URL
            }
        },
        transformChapterUrl = { url ->
            when {
                url.startsWith("http") -> url // Already absolute
                url.startsWith("//") -> "https:$url" // Protocol-relative
                else -> buildUrl(baseUrl, url) // Relative - add base URL
            }
        },
        transformCoverUrl = { coverUrl, _ ->
            when {
                coverUrl.startsWith("http") -> coverUrl // Already absolute
                coverUrl.startsWith("//") -> "https:$coverUrl" // Protocol-relative
                coverUrl.isBlank() -> "" // Empty
                else -> baseUrl + coverUrl // Relative - add base URL
            }
        }
    )

}
