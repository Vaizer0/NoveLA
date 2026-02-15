package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import timber.log.Timber
import org.jsoup.nodes.Document

class BacaLightnovel(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    override val id = "baca_lightnovel"
    override val nameStrId = R.string.source_name_baca_lightnovel
    override val baseUrl = "https://bacalightnovel.co/"
    override val catalogUrl = "https://bacalightnovel.co/series/"
    override val language = LanguageCode.INDONESIAN
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/bacalightnovel.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".listupd > .maindet .mdthumb a"),
            title = attr("title", "img").Clean(),
            url = attr("href", "a[href]"),
            cover = attr("src", "img")
        ),

        // Search selectors (same structure as catalog)
        search = SearchSelectors(
            item = elements(".listupd > .maindet .mdthumb a"),
            title = attr("title", "img").Clean(),
            url = attr("href", "a[href]"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1.entry-title").Clean(),
            cover = attr("src", ".sertothumb img"),
            description = text(".entry-content"),
            latestChapterHash = text(".epcurlast").Clean()
        ),

        chapters = ChapterSelectors(
            list = elements(".eplister li > a:not(.dlpdf)"),
            title = text(".epl-title"),
            content = text(".epcontent[itemprop=text] .text-left")
                .removeElementsDOM("script", ".ads")
                .applyStandardContentTransforms(baseUrl)

        ),


        // Chapters pagination - NONE (simple list)
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
            else "$catalogUrl?page=${index + 1}&order=populer"
        },
        buildSearchUrl = { index, query ->
            if (index == 0) "${baseUrl.removeSuffix("/")}/?s=$query"
            else "${baseUrl.removeSuffix("/")}/page/${index + 1}/?s=$query"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}
