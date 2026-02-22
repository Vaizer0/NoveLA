package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class PiaoTia(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // Идентификаторы источника
    override val id = "piaotia"
    override val nameStrId = R.string.source_name_piaotia
    override val baseUrl = "https://www.piaotia.com"
    override val catalogUrl = "$baseUrl/modules/article/index.php?fullflag=1&page=1"
    override val language = LanguageCode.CHINESE
    override val charset = "GBK"
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/$id.png"

    // Методы интерфейса
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    // Переопределяем getChapterText для обработки document.write
    override suspend fun getChapterText(doc: Document): String {
        // Сайт использует document.write() для создания div#content
        // Заменяем script на div перед парсингом
        val rawHtml = doc.html()
            .replace("<script language=\"javascript\">GetFont();</script>", "<div id=\"content\">")
            .replace("<script language=javascript>GetFont();</script>", "<div id=\"content\">")
        
        val fixedDoc = Jsoup.parse(rawHtml, doc.location())
        
        // Извлекаем контент
        val contentElement = fixedDoc.selectFirst("div#content")
        if (contentElement != null) {
            // Удаляем мусор
            contentElement.select("h1, script, div, table").remove()
            return TextExtractor.get(contentElement)
        }
        
        return ""
    }

    // Конфигурация селекторов
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,
        charset = charset,

        // Каталог: таблица книг
        catalog = CatalogSelectors(
            item = elements("table.grid tr:not(:first-child)"),
            title = text("td.odd a").Clean(),
            url = attr("href", "td.odd a"),
            cover = attr("src", ":not(*)")  // обложек нет в каталоге
        ),

        // Поиск: те же селекторы что каталог
        search = SearchSelectors(
            item = elements("table.grid tr:not(:first-child)"),
            title = text("td.odd a").Clean(),
            url = attr("href", "td.odd a"),
            cover = attr("src", ":not(*)")
        ),

        // Страница книги (bookinfo)
        book = BookSelectors(
            title = text("div#content h1").Clean(),
            cover = attr("src", "div#content img"),
            description = text("div[style*='float:left']").Clean(),
            latestChapterHash = attr("href", "table.grid a[href*='html']:first-of-type")
        ),

        // Главы
        chapters = ChapterSelectors(
            list = elements("div.centent ul li a, div#content ul li a"),
            title = null,
            content = text("div#content")
                .removeElementsDOM("h1", "script", "div", "table")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Список глав через AJAX
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, nc ->
            val chapterListUrl = when {
                bookUrl.contains("/bookinfo/") -> 
                    bookUrl.replace("/bookinfo/", "/html/").replace(".html", "/")
                bookUrl.endsWith("/index.html") -> 
                    bookUrl.replace("/index.html", "/")
                bookUrl.endsWith(".html") -> 
                    bookUrl.replace(".html", "/")
                else -> bookUrl.trimEnd('/') + "/"
            }
            
            nc.get(chapterListUrl).toDocument("GBK")
                .select("div.centent ul li a, div#content ul li a")
                .map { element ->
                    val href = element.attr("href")
                    val url = when {
                        href.startsWith("http") -> href
                        href.startsWith("/") -> "$baseUrl${href.trimStart('/')}"
                        else -> URI(chapterListUrl).resolve(href).toString()
                    }
                    ChapterResult(
                        title = element.text().trim(),
                        url = url
                    )
                }
        },

        // URL builders
        buildCatalogUrl = { index -> 
            "$baseUrl/modules/article/index.php?fullflag=1&page=${index + 1}"
        },
        buildSearchUrl = { _, _ -> "" }, // используется customSearchProvider

        // Обработка редиректа когда поиск находит 1 книгу
        customSearchProvider = { query, index, nc ->
            val encoded = URLEncoder.encode(query, "GBK")
            val searchUrl = "$baseUrl/modules/article/search.php?searchtype=articlename&searchkey=$encoded&Submit=%CB%D1+%CB%F7&page=${index + 1}"
            val response = nc.get(searchUrl)
            val doc = response.toDocument("GBK")
            
            // Если редирект на страницу книги
            if (doc.location().contains("/bookinfo/")) {
                val title = doc.selectFirst("div#content h1")?.text()?.trim() ?: ""
                listOf(BookResult(title = title, url = doc.location(), coverImageUrl = buildCoverUrl(doc.location())))
            } else {
                // Обычный список результатов
                doc.select("table.grid tr:not(:first-child)").map { item ->
                    val titleEl = item.selectFirst("td.odd a")
                    BookResult(
                        title = titleEl?.text()?.trim() ?: "",
                        url = titleEl?.attr("abs:href") ?: "",
                        coverImageUrl = ""
                    )
                }
            }.let { books -> PagedList(list = books, index = index, isLastPage = books.isEmpty()) }
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        
        // Динамическое построение URL обложки
        transformCoverUrl = { _, bookUrl -> buildCoverUrl(bookUrl) }
    )

    companion object {
        fun buildCoverUrl(bookUrl: String): String {
            val regex = Regex("""/(\d+)/(\d+)(?:\.html|/)""")
            val match = regex.find(bookUrl) ?: return ""
            val folderId = match.groupValues[1]
            val bookId = match.groupValues[2]
            return "https://www.piaotia.com/files/article/image/$folderId/$bookId/${bookId}s.jpg"
        }
    }
}