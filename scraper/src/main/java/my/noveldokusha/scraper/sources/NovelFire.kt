package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import org.jsoup.nodes.Document

class NovelFire(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "novelfire"
    override val nameStrId = R.string.source_name_novelfire
    override val baseUrl = "https://novelfire.net"
    override val catalogUrl = "$baseUrl/search-adv"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelfire.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements(".novel-list > .novel-item"),
            title = text(".novel-title").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("data-src", "img")
        ),

        search = SearchSelectors(
            item = elements(".novel-list.chapters .novel-item"),
            title = text(".novel-title").Clean(),
            url = attr("href", "a"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1.novel-title").Clean(),
            cover = attr("src", "img[src*='server-1'], .cover img"),
            description = text(".summary .content, .summary")
                .removeElementsDOM("h4.lined")
        ),

        chapters = ChapterSelectors(
            list = elements("a[href*='/chapter-']"),
            title = text("a"),
            content = text("#content, .chapter-content, div.entry-content")
                .removeElementsDOM("script", "nav", ".ads", ".advertisement", ".disqus", ".comments", ".c-message", ".nav-next", ".nav-previous")
                .applyStandardContentTransforms(baseUrl)
        ),

        chapterPaginationType = ChapterPaginationType.PAGE_BASED,
        chapterPaginationConfig = ChapterPaginationConfig(
            maxPageExtractor = { doc ->
                // Extract max page from pagination links like ".../chapters?page=48"
                // Use .pagination class (ul.pagination) not nav.pagination
                val lastPageLink = doc.select(".pagination a[href*='?page=']")
                    .mapNotNull { 
                        Regex("page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() 
                    }
                    .maxOrNull()
                lastPageLink ?: 1
            },
            pageUrlBuilder = { bookUrl, page ->
                val bookSlug = bookUrl.substringAfterLast("/")
                "$baseUrl/book/$bookSlug/chapters?page=$page"
            },
            chapterSelector = "a[href*='/chapter-']"
        ),



        buildCatalogUrl = { index ->
            val page = index + 1
            "$catalogUrl?ctgcon=and&totalchapter=0&ratcon=min&rating=0&status=-1&sort=rank-top&page=$page"
        },

        buildSearchUrl = { index, query ->
            val page = index + 1
            "$baseUrl/search?keyword=$query&page=$page"
        },

        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}