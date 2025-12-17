package my.noveldokusha.scraper.sources

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Bookhamster(
    private val networkClient: NetworkClient,
) : SourceInterface.Catalog {
    override val id = "bookhamster"
    override val nameStrId = R.string.source_name_bookhamster
    override val baseUrl = "https://bookhamster.ru/"
    override val catalogUrl = baseUrl
    override val language = LanguageCode.RUSSIAN

    override suspend fun getChapterTitle(doc: Document): String? = withContext(Dispatchers.Default) {
        doc.selectFirst("h1, .chapter-title")?.text()?.takeIf { it.isNotBlank() }
    }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val article = doc.selectFirst("article")
        if (article != null) {
            // Remove script tags
            article.select("script").remove()
            // Handle images with srcset
            article.html().replace(Regex("srcset=\"([^\"]+)\"")) { match ->
                val srcset = match.groupValues[1]
                val bestLink = srcset
                    .split(" ")
                    .filter { it.startsWith("http") }
                    .lastOrNull()
                if (bestLink != null) {
                    "src=\"$bestLink\""
                } else {
                    match.value
                }
            }.let { TextExtractor.get(Jsoup.parse(it).body()) }
        } else {
            ""
        }
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.call(getRequest(bookUrl)).toDocument()
                doc.selectFirst(".img-ranobe > img")?.attr("src")
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.call(getRequest(bookUrl)).toDocument()
                doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.call(getRequest(bookUrl)).toDocument()
                val chapters = mutableListOf<ChapterResult>()

                doc.select("div.li-ranobe").forEach { element ->
                    val link = element.selectFirst("a")
                    val name = link?.text()?.trim()
                    val url = link?.attr("href")

                    // Skip if it's a paid chapter (has buy-ranobe label)
                    if (element.selectFirst("label.buy-ranobe") == null && name != null && url != null) {
                        chapters.add(ChapterResult(
                            title = name,
                            url = url
                        ))
                    }
                }

                chapters.reversed()
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url = "$baseUrl/vse-knigi/?sort=По+рейтингу&bpage=$page"

                val doc = networkClient.call(getRequest(url)).toDocument()
                val books = mutableListOf<BookResult>()

                doc.select("div.one-book-home > div.img-home a").forEach { element ->
                    val title = element.attr("title")?.trim()
                    val coverUrl = element.selectFirst("img")?.attr("src")
                    val bookUrl = element.attr("href")

                    if (title != null && bookUrl != null) {
                        books.add(BookResult(
                            title = title,
                            url = bookUrl,
                            coverImageUrl = coverUrl ?: ""
                        ))
                    }
                }

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
                )
            }
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String,
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank() || index > 0)
                return@tryConnect PagedList.createEmpty(index = index)

            val query = URLEncoder.encode(input, StandardCharsets.UTF_8.name())
            val url = "$baseUrl/vse-knigi/?searchname=$query&bpage=1"

            val doc = networkClient.call(getRequest(url)).toDocument()
            val books = mutableListOf<BookResult>()

            doc.select("div.one-book-home > div.img-home a").forEach { element ->
                val title = element.attr("title")?.trim()
                val coverUrl = element.selectFirst("img")?.attr("src")
                val bookUrl = element.attr("href")

                if (title != null && bookUrl != null) {
                    books.add(BookResult(
                        title = title,
                        url = bookUrl,
                        coverImageUrl = coverUrl ?: ""
                    ))
                }
            }

            PagedList(
                list = books,
                index = index,
                isLastPage = true
            )
        }
    }
}
