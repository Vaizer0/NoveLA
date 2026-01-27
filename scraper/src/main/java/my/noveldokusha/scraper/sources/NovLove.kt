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
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.add
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.postPayload
import org.jsoup.nodes.Document

class NovLove(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "NovLove"
    override val nameStrId = R.string.source_name_novlove
    override val baseUrl = "https://novlove.com/"
    override val catalogUrl = "https://novlove.com/sort/nov-love-daily-update"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novlove.png"
    override val iconResId = null

    // Declarative configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Catalog selectors
        catalog = CatalogSelectors(
            item = elements(".col-novel-main .row"),
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("data-src", "img.cover")
        ),

        // Search selectors
        search = SearchSelectors(
            item = elements(".col-novel-main .row"),
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("src", "img.cover")
        ),

        // Book selectors
        book = BookSelectors(
            title = text("h3.title").Clean(),
            cover = attr("content", "meta[itemprop=image]"),
            description = text(".desc-text")
        ),

        // Chapters selectors
        chapters = ChapterSelectors(
            list = elements("#list-chapter .list-chapter li a"),
            title = attr("title", "a"),
            content = text("#chr-content")
                .applyStandardContentTransforms(baseUrl)
                .removeElementsDOM("script", ".ads", ".advertisement", ".social-share")

        ),

        // Chapters pagination - AJAX based
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
            try {
                // Убираем toIntOrNull(), так как novelId — это строка (slug)
                val novelId = bookUrl.removeSuffix("/").substringAfterLast("/")
                val ajaxUrl = "${baseUrl.removeSuffix("/")}/ajax/chapter-archive?novelId=$novelId"
                val response = networkClient.get(ajaxUrl)
                val ajaxDoc = response.toDocument()
                ajaxDoc.select("a[href*='/chapter']").map { element ->
                    ChapterResult(
                        // Используем attr("title") для чистого названия без мусора
                        title = element.attr("title").ifBlank { element.text() }.trim(),
                        url = element.attr("abs:href").ifBlank { baseUrl.removeSuffix("/") + element.attr("href") }
                    )
                }
            } catch (e: Exception) {
                Timber.w("Failed to load chapters for NovLove: ${e.message}")
                emptyList()
            }
        },


        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            if (page == 1) catalogUrl
            else "${baseUrl}sort/nov-love-daily-update?page=$page"
        },

        buildSearchUrl = { index, query ->
            val page = index + 1
            val builder = "${baseUrl}search".toUrlBuilderSafe()
            builder.add("keyword", query)
            if (page > 1) builder.add("page", page)
            builder.toString()
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.novelBinCatalogCoverUrl()
    )

}
