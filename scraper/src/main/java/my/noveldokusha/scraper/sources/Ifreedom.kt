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

class Ifreedom(
    private val networkClient: NetworkClient,
) : SourceInterface.Catalog {
    override val id = "ifreedom"
    override val nameStrId = R.string.source_name_ifreedom
    override val baseUrl = "https://ifreedom.su/"
    override val catalogUrl = baseUrl
    override val language = LanguageCode.RUSSIAN

    override suspend fun getChapterTitle(doc: Document): String? = withContext(Dispatchers.Default) {
        doc.selectFirst("h1, .chapter-title")?.text()?.takeIf { it.isNotBlank() }
    }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        // Try multiple selectors for chapter content
        val contentSelectors = listOf(
            "article",
            ".chapter-content",
            ".content",
            ".text",
            ".chapter-text",
            "#content",
            ".post-content"
        )

        for (selector in contentSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                // Remove unwanted elements
                element.select("script, .ads, .advertisement").remove()

                // Handle images with srcset
                val html = element.html().replace(Regex("srcset=\"([^\"]+)\"")) { match ->
                    val srcset = match.groupValues[1]
                    val bestLink = srcset
                        .split(" ")
                        .filter { it.startsWith("http") }
                        .lastOrNull()
                        ?: match.value
                    "src=\"$bestLink\""
                }

                val body = Jsoup.parse(html).body()
                return@withContext if (body != null) TextExtractor.get(body) else html
            }
        }

        // Fallback: try to extract from the whole document
        doc.select("script, .ads, .advertisement").remove()
        val body = doc.body()
        if (body != null) {
            TextExtractor.get(body)
        } else {
            ""
        }
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.call(getRequest(bookUrl)).toDocument()
                doc.selectFirst("div.book-img.block-book-slide-img > img")?.attr("src")
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

                doc.select("div.chapterinfo").forEach { element ->
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
                val url = "${baseUrl}vse-knigi/?sort=По+рейтингу&bpage=$page"

                val doc = networkClient.call(getRequest(url)).toDocument()
                val books = mutableListOf<BookResult>()

                // Use correct selector for book items
                doc.select(".item-book-slide").forEach { bookElement ->
                    val linkElement = bookElement.selectFirst("a")
                    val title = bookElement.selectFirst(".block-book-slide-title")?.text()?.trim()
                        ?: linkElement?.attr("title")?.trim()
                        ?: ""

                    val coverUrl = bookElement.selectFirst("img")?.attr("src") ?: ""
                    val bookUrl = linkElement?.attr("href") ?: ""

                    if (title.isNotEmpty() && bookUrl.isNotEmpty()) {
                        books.add(BookResult(
                            title = title,
                            url = bookUrl,
                            coverImageUrl = coverUrl
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
            val url = "${baseUrl}vse-knigi/?searchname=$query&bpage=1"

            val doc = networkClient.call(getRequest(url)).toDocument()
            val books = mutableListOf<BookResult>()

            // Use correct selector for book items in search
            doc.select(".item-book-slide").forEach { bookElement ->
                val linkElement = bookElement.selectFirst("a")
                val title = bookElement.selectFirst(".block-book-slide-title")?.text()?.trim()
                    ?: linkElement?.attr("title")?.trim()
                    ?: ""

                val coverUrl = bookElement.selectFirst("img")?.attr("src") ?: ""
                val bookUrl = linkElement?.attr("href") ?: ""

                if (title.isNotEmpty() && bookUrl.isNotEmpty()) {
                    books.add(BookResult(
                        title = title,
                        url = bookUrl,
                        coverImageUrl = coverUrl
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
