package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.*
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import org.jsoup.nodes.Document

class WuxiaWorld_site(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "wuxia_world_site"
    override val nameStrId = R.string.source_name_wuxia_world_site
    override val baseUrl = "https://wuxiaworld.site/"
    override val catalogUrl = "https://wuxiaworld.site/novel/?m_orderby=trending"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/wuxiaworld.site.png"
    override val iconResId = null

    // Declarative configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Catalog selectors
        catalog = CatalogSelectors(
            item = elements(".page-item-detail"),
            title = text(".post-title h3 a").Clean(),
            url = attr("href", ".post-title h3 a"),
            cover = attr("data-src", ".c-image-hover img")
        ),

        // Search selectors (same as catalog)
        search = SearchSelectors(
            item = elements(".c-tabs-item__content"),
            title = text(".post-title h3 a").Clean(),
            url = attr("href", ".post-title h3 a"),
            cover = attr("data-src", ".c-image-hover img")
        ),

        // Book selectors
        book = BookSelectors(
            title = text("h1").Clean(),
            cover = attr("data-src", ".summary_image img"),
            description = text(".summary__content")
        ),

        // Chapters selectors
        chapters = ChapterSelectors(
            list = elements("li.wp-manga-chapter"),
            title = text("a"),
            content = text(".reading-content")
                .removeElementsDOM("script", ".ads", ".advertisement", ".social-share")
                .applyStandardContentTransforms(baseUrl)
        ),


        // Chapters pagination - AJAX based
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl: String, networkClient: NetworkClient ->
            try {
                val ajaxUrl = "${bookUrl.removeSuffix("/")}/ajax/chapters/"
                val ajaxDoc = networkClient.call(postRequest(ajaxUrl)).toDocument()

                ajaxDoc.select("li.wp-manga-chapter a[href]").map { element ->
                    ChapterResult(
                        title = element.text().trim(),
                        url = element.attr("href").let { href ->
                            if (href.startsWith("http")) href else baseUrl.removeSuffix("/") + href
                        }
                    )

                }
            } catch (e: Exception) {
                emptyList()
            }
        },

        // Chapters order - reverse (newest first to oldest first)
        reverseChapters = true,

        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            if (page == 1) catalogUrl
            else "$catalogUrl&page=$page"
        },

        buildSearchUrl = { index, query ->
            val page = index + 1
            if (page == 1) "${baseUrl}?s=$query&post_type=wp-manga"
            else "${baseUrl}page/$page/?s=$query&post_type=wp-manga"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}
