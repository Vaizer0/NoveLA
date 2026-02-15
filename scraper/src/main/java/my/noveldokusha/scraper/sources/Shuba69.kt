package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.CloudflareConfig
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.DetectionRule
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.buildUrl
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document
import java.net.URI

class Shuba69(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // Методы интерфейса (ОБЯЗАТЕЛЬНО!)
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    // Идентификаторы источника
    override val id = "shuba69"
    override val nameStrId = R.string.source_name_69shuba
    override val baseUrl = "https://www.69shuba.com/"
    override val catalogUrl = "https://www.69shuba.com/novels/monthvisit_0_0_1.htm"
    override val language = LanguageCode.CHINESE
    override val charset = "GBK" // GBK кодировка для китайского контента
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/69shuba.png"
    override val iconResId = null

    // Declarative selectors configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,
        charset = charset, // GBK encoding for Chinese content

        // Declarative selectors
        catalog = CatalogSelectors(
            item = elements("ul[id=\"article_list_content\"] li"),
            title = text("div.newnav h3 a").Clean(),
            url = attr("href", "a.imgbox"),
            cover = attr("data-src", "img")
        ),

        // Search selectors (different structure)
        search = SearchSelectors(
            item = elements("div.newbox ul li"),
            title = text("h3 a:last-child").Clean(),
            url = attr("href", "a.imgbox"),
            cover = attr("data-src", "img")
        ),

        book = BookSelectors(
            title = text("div.booknav2 h1 a").Clean(),
            cover = attr("src", "div.bookimg2 img"),
            description = text("div.navtxt"),
            latestChapterHash = text(".infolist li:nth-child(2)").Clean()
        ),

        chapters = ChapterSelectors(
            list = elements("div#catalog ul li a"),
            title = text("a"),
            content = text("div.txtnav")
                .removeElementsDOM("h1", "div.txtinfo", "div.bottom-ad", "div.bottem2", ".visible-xs", "script")
                .applyStandardContentTransforms(baseUrl)
        ),

        // POST search with GBK encoding
        postSearchEnabled = true,
        postSearchUrl = "https://www.69shuba.com/modules/article/search.php",
        postSearchDataBuilder = { query ->
            // Encode search query in GBK
            val encodedQuery = java.net.URLEncoder.encode(query, "GBK")
            mapOf(
                "searchkey" to encodedQuery,
                "searchtype" to "all" // Required parameter for this site
            )
        },

        // Chapters - special URL processing
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, networkClient ->
            // Convert /txt/A43616.htm to /A43616/ for chapter list
            val chapterListUrl = bookUrl.replace("/txt/", "/").replace(".htm", "/")

            // Site lists chapters newest-first; reverse so we return oldest-first
            networkClient.get(chapterListUrl).toDocument("GBK")
                .select("div#catalog ul li a")
                .map { element ->
                    ChapterResult(
                        title = element.text().trim(),
                        url = URI(baseUrl).resolve(element.attr("href")).toString()
                    )
                }
                .asReversed()
        },

        // URL builders
        buildCatalogUrl = { index -> "${baseUrl}novels/monthvisit_0_0_${index + 1}.htm" },
        buildSearchUrl = { index, query ->
            if (index == 0) "" // POST search, URL not used
            else "" // Search only on first page
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )

    // Note: URL transformers are now inline in HtmlSelectors config
}
