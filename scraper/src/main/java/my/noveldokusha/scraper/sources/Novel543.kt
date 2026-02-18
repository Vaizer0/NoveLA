package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document
import java.net.URI

class Novel543(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "novel543"
    override val nameStrId = R.string.source_name_novel543
    override val baseUrl = "https://www.novel543.com/"
    override val catalogUrl = "https://www.novel543.com/bookstack/"
    override val language = LanguageCode.CHINESE
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novel543.png"
    override val iconResId = null

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    /**
     * Кастомная загрузка контента главы со склейкой подстраниц.
     *
     * novel543.com разбивает главы на подстраницы по паттерну:
     *   Глава N, часть 1: {prefix}_{N}.html
     *   Глава N, часть 2: {prefix}_{N}_2.html
     *   Глава N, часть 3: {prefix}_{N}_3.html   (если есть)
     *
     * Признак наличия следующей подстраницы — ссылка в HTML с href,
     * совпадающим с паттерном {chapterFile}_\d+\.html.
     * Это надёжнее, чем перебирать URL вслепую.
     */
    override suspend fun getChapterText(doc: Document): String {
        // Из URL "…/8096_1.html" получаем "8096_1"
        val chapterFile = doc.location().substringAfterLast("/").removeSuffix(".html")
        val baseDir = doc.location().substringBeforeLast("/") + "/"

        // Паттерн подстраницы: "8096_1_2.html", "8096_1_3.html", и т.д.
        val subPagePattern = Regex("${Regex.escape(chapterFile)}_\\d+\\.html$")

        // Извлечение текста через декларативный конфиг — правильно обрабатывает <p> и <br>
        val allContent = StringBuilder(getChapterText(config, doc))
        var currentDoc = doc

        // Ищем ссылки на подстраницы в текущем документе
        repeat(20) { // Предохранительный лимит
            val subLink = currentDoc.select("a[href]").firstOrNull { el ->
                el.attr("href").substringAfterLast("/").matches(subPagePattern)
            } ?: return@repeat // Нет ссылки на подстраницу — конец

            val subFile = subLink.attr("href").substringAfterLast("/")
            currentDoc = networkClient.get(baseDir + subFile).toDocument()
            allContent.append("\n\n").append(getChapterText(config, currentDoc))
        }

        return allContent.toString()
    }

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Каталог: /bookstack/?page=N
        catalog = CatalogSelectors(
            item = elements("ul.list li.media"),
            title = text("div.media-content h3 a").Clean(),
            url = attr("href", "div.media-left a"),
            cover = attr("src", "div.media-left img")
        ),

        // Поиск: такая же структура, что и каталог
        search = SearchSelectors(
            item = elements("ul.list li.media"),
            title = text("div.media-content h3 a").Clean(),
            url = attr("href", "div.media-left a"),
            cover = attr("src", "div.media-left img")
        ),

        // Страница книги: /{bookId}/
        book = BookSelectors(
            title = text("h1.title").Clean(),
            cover = attr("src", ".cover img"),
            description = text("div.intro").Clean(),
            // Дата обновления — надёжный хэш для обнаружения новых глав
            latestChapterHash = text("p.meta span.iconf:last-child").Clean()
        ),

        // Список глав: загружается через /{bookId}/dir
        chapters = ChapterSelectors(
            list = elements("ul.all li a"),
            title = text("a").Clean(),
            content = text("div.content")
                .removeElementsDOM("div.gadBlock", "script", "ins", ".ads", ".ad", "p:contains(溫馨提示)")
        ),

        // Список глав через отдельную страницу /dir
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, nc ->
            val dirUrl = bookUrl.trimEnd('/') + "/dir"
            nc.get(dirUrl).toDocument()
                .select("ul.all li a")
                .map { el ->
                    ChapterResult(
                        title = el.text().trim(),
                        url = URI(baseUrl).resolve(el.attr("href")).toString()
                    )
                }
        },

        // Поиск: только первая страница (пагинация неизвестна)
        searchNoPagination = true,

        buildCatalogUrl = { index -> "https://www.novel543.com/bookstack/?page=${index + 1}" },
        buildSearchUrl = { index, query ->
            if (index == 0) "https://www.novel543.com/search/${java.net.URLEncoder.encode(query, "UTF-8")}"
            else ""
        },

        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )
}
