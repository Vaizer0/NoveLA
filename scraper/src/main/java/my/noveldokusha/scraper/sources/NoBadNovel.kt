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
import org.jsoup.nodes.Document

class NoBadNovel(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "nobadnovel"
    override val nameStrId = R.string.source_name_nobadnovel
    override val baseUrl = "https://www.nobadnovel.com/"
    override val catalogUrl = "https://www.nobadnovel.com/series"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/nobadnovel.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".grid > div"),
            title = text("h4 a").Clean(),
            url = attr("href", "a[href*=/series/]"),
            cover = attr("src", "img[src]")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements(".grid > div"),
            title = text("h4 a").Clean(),
            url = attr("href", "a[href*=/series/]"),
            cover = attr("src", "img[src]")
        ),

        book = BookSelectors(
            cover = attr("src", "img[src*=cdn.nobadnovel]"),
            description = text("#intro .content")
        ),

        chapters = ChapterSelectors(
            list = elements(".chapter-list a[href]"),
            title = text("a",),
            content = text("div.text-base.sm\\:text-lg, div[class*=text-base]")
                .removeElementsDOM("script", ".ads", ".adblock-service")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Chapters pagination - NONE (simple list)
        chapterPaginationType = ChapterPaginationType.NONE,

        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            if (index == 0) catalogUrl
            else "$catalogUrl/page/${index + 1}"
        },
        buildSearchUrl = { index, query ->
            if (index == 0) "${baseUrl.removeSuffix("/")}/series?keyword=$query"
            else "${baseUrl.removeSuffix("/")}/series/page/${index + 1}?keyword=$query"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}
