package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

class Ifreedom(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "ifreedom"
    override val nameStrId = R.string.source_name_ifreedom
    override val baseUrl = "https://ifreedom.su/"
    override val catalogUrl = "$baseUrl"
    override val language = LanguageCode.RUSSIAN
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/ifreedom.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements(".booksearch .item-book-slide"),
            title = text(".block-book-slide-title").Clean(),
            url = attr("href", "a"),
            cover = attr("src", "img")
                .applyStandardContentTransforms(baseUrl)
                .removeElementsDOM("script", ".ads")
        ),

        search = SearchSelectors(
            item = elements(".booksearch .item-book-slide"),
            title = text(".block-book-slide-title").Clean(),
            url = attr("href", "a"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1").Clean(),
            cover = attr("src", "div.book-img.block-book-slide-img > img"),
            description = text("[data-name=\"Описание\"]")
        ),

        chapters = ChapterSelectors(
            list = elements("div.chapterinfo a"),
            title = text("a"),
            content = text(".chapter-content")
                .removeElementsDOM("script", ".ads", ".pc-adv", ".mob-adv")
                .applyStandardContentTransforms(baseUrl)
        ),

        chapterPaginationType = ChapterPaginationType.NONE,

        reverseChapters = true,

        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        buildCatalogUrl = { index -> "${baseUrl}vse-knigi/?sort=По+рейтингу&bpage=${index + 1}" },
        buildSearchUrl = { index, query ->
            "${baseUrl}vse-knigi/?searchname=$query&bpage=${index + 1}"
        },

        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )

}
