package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
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

class ReadNovelFull(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "read_novel_full"
    override val nameStrId = R.string.source_name_read_novel_full
    override val baseUrl = "https://readnovelfull.com/"
    override val catalogUrl = "https://readnovelfull.com/novel-list/most-popular-novel"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/readnovelfull.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".col-novel-main .row"),
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("src", "div.col-xs-3 > div > img")
        ),

        // Search selectors (same structure as catalog)
        search = SearchSelectors(
            item = elements(".col-novel-main .row"),
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("src", "div.col-xs-3 > div > img")
        ),

        book = BookSelectors(
            title = text("h3.title").Clean(),
            cover = attr("src", ".book img[src]"),
            description = text("#tab-description")
        ),

        chapters = ChapterSelectors(
            list = elements("a[href]"),
            title = text(".chr-text h1, .chapter-title"),
            content = text("#chr-content")
                .removeElementsDOM("script", ".ads", ".advertisement")
                .applyStandardContentTransforms(baseUrl)
        ),


        // Chapters pagination - AJAX based
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
            try {
                // Extract novelId from #rating[data-novel-id]
                val pageDoc = networkClient.get(bookUrl).toDocument()
                val novelId = pageDoc.selectFirst("#rating[data-novel-id]")
                    ?.attr("data-novel-id")
                    ?: return@ajaxChapterListProvider emptyList()

                // AJAX request to chapter-archive
                val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
                val ajaxDoc = networkClient.get(ajaxUrl).toDocument()

                // Parse chapters from AJAX response
                ajaxDoc.select("a[href]").map { element ->
                    ChapterResult(
                        title = element.attr("title").ifBlank { element.text() }.trim(),
                        url = element.attr("href").let { href ->
                            if (href.startsWith("http")) href else baseUrl + href.removePrefix("/")
                        }
                    )
                }
            } catch (e: Exception) {
                Timber.w("Failed to get chapters for ReadNovelFull URL $bookUrl: ${e.message}")
                emptyList()
            }
        },

        // POST search disabled
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            if (index == 0) catalogUrl
            else "$catalogUrl?page=${index + 1}"
        },
        buildSearchUrl = { index, query ->
            if (index == 0) "${baseUrl.removeSuffix("/")}/novel-list/search?keyword=$query"
            else "${baseUrl.removeSuffix("/")}/novel-list/search?keyword=$query&page=${index + 1}"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.readNovelFullCoverUrl()
    )
}
