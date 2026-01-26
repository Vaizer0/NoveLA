package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
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

class Novelku(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "novelku"
    override val nameStrId = R.string.source_name_novelku
    override val baseUrl = "https://novelku.id/"
    override val catalogUrl = "https://novelku.id/"
    override val language = LanguageCode.INDONESIAN
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelku.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements("div.page-item-detail .item-thumb a"),
            title = attr("title", "a").Clean(),
            url = attr("href", "a"),
            cover = attr("data-src", "img")
        ),

        // Search selectors (different structure)
        search = SearchSelectors(
            item = elements(".c-tabs-item__content .tab-thumb a"),
            title = attr("title", "a").Clean(),
            url = attr("href", "a"),
            cover = attr("data-src", "img")
        ),

        book = BookSelectors(
            cover = attr("data-src", ".summary_image img"),
            description = text(".summary__content")
        ),

        chapters = ChapterSelectors(
            list = elements("li[class*=wp-manga-chapter] a"),
            title = text("a"),
            content = text(".read-container .text-left")
                .removeElementsDOM("script")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Chapters pagination - NONE (all chapters loaded on page)
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
            else "$catalogUrl/page/${index + 1}/"
        },
        buildSearchUrl = { index, query ->
            if (index == 0) "${baseUrl.removeSuffix("/")}/?s=$query&post_type=wp-manga"
            else "${baseUrl.removeSuffix("/")}/page/${index + 1}/?s=$query&post_type=wp-manga"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}
