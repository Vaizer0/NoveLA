package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
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

class NovelBuddy(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    override val id = "novelbuddy"
    override val nameStrId = R.string.source_name_novelbuddy
    override val baseUrl = "https://novelbuddy.io"
    override val catalogUrl = "https://novelbuddy.io/search"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelbuddy.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements(".book-detailed-item"),
            title = text(".title").Clean(),
            url = attr("href", "h3 a"),
            cover = attr("data-src", ".thumb img")
        ),

        search = SearchSelectors(
            item = elements(".book-detailed-item"),
            title = text(".title").Clean(),
            url = attr("href", "h3 a"),
            cover = attr("data-src", ".thumb img")
        ),

        book = BookSelectors(
            title = text("h1").Clean(),
            cover = attr("data-src", ".img-cover img"),
            description = text(".section-body.summary .content")
                .removeElementsDOM("h3"),
            latestChapterHash = text(".meta p:has(strong:contains(Chapters)) span").Clean()
        ),

        chapters = ChapterSelectors(
            list = elements("li"),
            title = null,
            content = text(".content-inner")
                .removeElementsDOM("script", "#listen-chapter", "#google_translate_element", ".ads", ".advertisement")
                .applyStandardContentTransforms(baseUrl)
        ),

        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, networkClient ->
            try {
                // 1. Получаем страницу и ищем bookId
                val response = networkClient.get(bookUrl)
                val bookDoc = response.toDocument()
                val scriptHtml = bookDoc.select("script").html()

                // Улучшенное регулярное выражение (иногда на сайте пробелы или var)
                val bookId = Regex("""bookId\s*=\s*(\d+)""").find(scriptHtml)?.groupValues?.getOrNull(1)

                if (bookId.isNullOrEmpty()) {
                    emptyList()
                } else {
                    // 2. Запрос к API (используем baseUrl, чтобы не хардкодить)
                    val ajaxUrl = buildUrl(baseUrl, "api/manga/$bookId/chapters?source=detail")
                    val ajaxDoc = networkClient.get(ajaxUrl).toDocument()

                    // 3. Парсим элементы списка
                    ajaxDoc.select("li").map { element ->
                        ChapterResult(
                            // Используем конкретный селектор strong, чтобы не цеплять дату обновления
                            title = element.select("strong.chapter-title").text().trim(),
                            url = baseUrl + element.select("a").attr("href")
                        )
                    }.asReversed() // Инвертируем, так как в API обычно новые сверху
                }
            } catch (e: Exception) {
                Timber.w("Failed to get chapters for NovelBuddy: ${e.message}")
                emptyList()
            }
        },



        buildCatalogUrl = { index ->
            val page = (index + 1).toString()
            val builder = buildUrl(baseUrl, "search").toUrlBuilderSafe()
            builder.add("sort", "views")
            if (index > 0) builder.add("page", page)
            builder.toString()
        },

        buildSearchUrl = { index, query ->
            val page = (index + 1).toString()
            val builder = buildUrl(baseUrl, "search").toUrlBuilderSafe()
            builder.add("q", query)
            if (index > 0) builder.add("page", page)
            builder.toString()
        },

        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = { url, _ ->
            when {
                url.isNullOrBlank() -> ""
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                else -> buildUrl(baseUrl, url)
            }
        }
    )
}