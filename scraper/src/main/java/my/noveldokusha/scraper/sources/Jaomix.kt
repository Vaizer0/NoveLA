package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

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


        // Chapters without pagination
        chapterPaginationType = ChapterPaginationType.NONE,

        // Chapters order - reverse (newest first)
        reverseChapters = true,
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
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )

    // Note: URL transformers are now inline in HtmlSelectors config
}
