package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toDocument
import okhttp3.Headers
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.security.SecureRandom
import kotlin.math.abs

class Quanben5(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // IDENTITY — идентификаторы источника
    override val id = "quanben5"
    override val nameStrId = R.string.source_name_quanben5
    override val baseUrl = "https://big5.quanben5.com/"
    override val catalogUrl = "https://big5.quanben5.com/category/1.html"
    override val language = LanguageCode.CHINESE
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/quanben5.png"
    override val iconResId = null

    // DELEGATE METHODS — делегируют в ScraperHelpers
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    // CONFIG — HtmlSelectors конфиг
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Каталог: карточки книг
        catalog = CatalogSelectors(
            item = elements(".pic_txt_list"),
            title = text("h3 a").Clean(),
            url = attr("href", "h3 a"),
            cover = attr("src", ".pic img")
        ),

        // Страница книги
        book = BookSelectors(
            title = text("span.name").Clean(),
            cover = attr("src", ".box .pic img"),
            description = text(".box .description")
                .removeElementsDOM("h2") // на всякий случай, если захватит заголовок
                .Clean(),

            latestChapterHash = null
        ),

        // Главы
        chapters = ChapterSelectors(
            list = elements("ul.list li a"),
            title = text("span"),
            content = text("#content")
                .removeElementsDOM("#ad", "script", "style")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Главы загружаются с отдельной страницы /xiaoshuo.html
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, nc ->
            val chaptersUrl = bookUrl.trimEnd('/') + "/xiaoshuo.html"
            nc.get(chaptersUrl).toDocument()
                .select("ul.list li a")
                .map { element ->
                    ChapterResult(
                        title = element.text().trim(),
                        url = URI(baseUrl).resolve(element.attr("href")).toString()
                    )
                }
        },

        // Кастомный поиск через JSONP API
        customSearchProvider = { input, index, nc ->
            if (index > 0) return@HtmlSelectors PagedList(listOf(), index = index, isLastPage = true)
            
            // JavaScript: encodeURI(keywords) - кодируем оригинальный input
            val encodedKeywords = encodeURI(input)
            // JavaScript: base64(encodeURI(keywords))
            val base64Encoded = customBase64Encode(encodedKeywords)
            // JavaScript: encodeURI(base64(...)) - кодируем результат (особенно % → %25)
            val bParam = encodeURI(base64Encoded)
            val timestamp = System.currentTimeMillis()

            val searchUrl = "$baseUrl?c=book&a=search.json&callback=search&t=$timestamp&keywords=$encodedKeywords&b=$bParam"

            // Сервер требует Referer header для поиска
            val headers = Headers.Builder()
                .add("Referer", "${baseUrl}search.html")
                .build()

            val response = nc.call(getRequest(searchUrl, headers)).body.string()
            val jsonContent = parseJsonPResponse(response) ?: return@HtmlSelectors PagedList(listOf(), index = index, isLastPage = true)

            val doc = Jsoup.parse(jsonContent, baseUrl)

            val items = doc.select(".pic_txt_list").mapNotNull { element ->
                val link = element.selectFirst("h3 a") ?: return@mapNotNull null
                val title = link.text().trim()
                val href = link.attr("href")
                val url = if (href.startsWith("http")) href else baseUrl.trimEnd('/') + href

                my.noveldokusha.scraper.domain.BookResult(
                    title = title,
                    url = url,
                    coverImageUrl = element.selectFirst(".pic img")?.absUrl("src") ?: ""
                )
            }

            PagedList(items, index = index, isLastPage = true)
        },

        // URL builders
        buildCatalogUrl = { index ->
            val page = index + 1
            if (page == 1) "${baseUrl}category/1.html"
            else "${baseUrl}category/1_$page.html"
        },
        buildSearchUrl = { _, _ -> "" }, // Используется customSearchProvider

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )

    // Кастомный алфавит для base64 кодирования (из JavaScript сайта)
    private val staticChars = "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN"
    private val random = SecureRandom()

    /**
     * Аналог JavaScript encodeURI - кодирует только специальные символы
     * В отличие от URLEncoder.encode, не кодирует: ; , / ? : @ & = + $ #
     * Главное: кодирует % как %25
     */
    private fun encodeURI(input: String): String {
        val result = StringBuilder()
        for (char in input) {
            when (char.code) {
                in 0x00..0x7F -> {
                    // ASCII символы
                    when (char) {
                        // Не кодируем (как JavaScript encodeURI)
                        ';', ',', '/', '?', ':', '@', '&', '=', '+', '$', '#', '-', '_', '.', '!', '~', '*', '\'', '(', ')' -> {
                            result.append(char)
                        }
                        // Буквы и цифры не кодируем
                        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> {
                            result.append(char)
                        }
                        // Остальные кодируем
                        else -> {
                            result.append(String.format("%%%02X", char.code))
                        }
                    }
                }
                // Unicode символы кодируем как %XX%XX%XX (UTF-8 байты)
                else -> {
                    val bytes = char.toString().toByteArray(Charsets.UTF_8)
                    for (byte in bytes) {
                        result.append(String.format("%%%02X", byte.toInt() and 0xFF))
                    }
                }
            }
        }
        return result.toString()
    }

    /**
     * Кастомное base64 кодирование как на сайте quanben5.com
     */
    private fun customBase64Encode(str: String): String {
        val result = StringBuilder()
        for (char in str) {
            val num0 = staticChars.indexOf(char)
            val code = if (num0 == -1) {
                char
            } else {
                staticChars[(num0 + 3) % 62]
            }
            val num1 = abs(random.nextInt()) % 62
            val num2 = abs(random.nextInt()) % 62
            result.append(staticChars[num1])
            result.append(code)
            result.append(staticChars[num2])
        }
        return result.toString()
    }

    /**
     * Парсинг JSONP ответа поиска
     */
    private fun parseJsonPResponse(response: String): String? {
        val startIndex = response.indexOf("({")
        val endIndex = response.lastIndexOf("})")
        if (startIndex == -1 || endIndex == -1) return null

        val jsonPart = response.substring(startIndex + 2, endIndex + 1)
        val contentStart = jsonPart.indexOf("\"content\":\"")
        if (contentStart == -1) return null

        val contentBegin = contentStart + "\"content\":\"".length
        var contentEnd = jsonPart.length - 2

        var i = contentBegin
        while (i < jsonPart.length) {
            if (jsonPart[i] == '"' && jsonPart.getOrNull(i - 1) != '\\') {
                contentEnd = i
                break
            }
            i++
        }

        val content = jsonPart.substring(contentBegin, contentEnd)
        return content
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .unescapeUnicode()
    }

    /**
     * Декодирование Unicode escape последовательностей \uXXXX
     */
    private fun String.unescapeUnicode(): String {
        val regex = Regex("\\\\u([0-9a-fA-F]{4})")
        return regex.replace(this) { match ->
            val code = match.groupValues[1].toInt(16)
            code.toChar().toString()
        }
    }
}