package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import timber.log.Timber
import org.jsoup.nodes.Document

class NovelBin(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "NovelBin"
    override val nameStrId = R.string.source_name_novelbin
    override val baseUrl = "https://novelbin.com/"
    override val catalogUrl = "https://novelbin.com/sort/top-view-novel"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelbin.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements(".col-novel-main .row"),
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("data-src", "img[data-src]")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements(".col-novel-main .row"),
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("src", "img[src]")
        ),

        book = BookSelectors(
            cover = attr("content", "meta[property='og:image']"),
            description = text("div.desc-text")
        ),

        chapters = ChapterSelectors(
            list = elements("ul.list-chapter li a"),
            title = attr("title", "a"),
            content = text("#chr-content")
                .removeElementsDOM("script", ".ads")
                .applyStandardContentTransforms(baseUrl)
        ),


        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
            try {
                // Получаем novelId из meta[property=og:url] на странице книги
                val response = networkClient.get(bookUrl)
                val doc = response.toDocument()
                val novelId = doc.selectFirst("meta[property=og:url]")
                    ?.attr("content")
                    ?.toUrlBuilderSafe()
                    ?.build()
                    ?.lastPathSegment
                    ?: return@ajaxChapterListProvider emptyList()

                val ajaxUrl = "https://novelbin.com/ajax/chapter-archive?novelId=$novelId"
                val ajaxResponse = networkClient.get(ajaxUrl)
                val ajaxDoc = ajaxResponse.toDocument()

                ajaxDoc.select("ul.list-chapter li a").map { element ->
                    ChapterResult(
                        title = element.text(),
                        url = element.attr("href")
                    )
                }
            } catch (e: Exception) {
                Timber.w("Failed to get chapters for NovelBin URL $bookUrl: ${e.message}")
                emptyList()
            }
        },

        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        buildCatalogUrl = { index -> Companion.buildCatalogUrl(baseUrl, index) },
        buildSearchUrl = { index, query -> Companion.buildSearchUrl(baseUrl, index, query) },

        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.novelBinCoverUrl()
    )

    companion object {
        fun buildCatalogUrl(baseUrl: String, index: Int): String {
            val page = index + 1
            val path = if (page == 1) "sort/top-view-novel" else "sort/top-view-novel?page=$page"
            return buildUrl(baseUrl, path)
        }

        fun buildSearchUrl(baseUrl: String, index: Int, input: String): String {
            val page = index + 1
            val path = if (page == 1) "search?keyword=$input" else "search?keyword=$input&page=$page"
            return buildUrl(baseUrl, path)
        }
    }


}
