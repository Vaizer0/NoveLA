package my.noveldokusha.scraper.sources

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toJson
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

class RanobeHub(
    private val networkClient: NetworkClient,
) : SourceInterface.Catalog {
    override val id = "ranobehub"
    override val nameStrId = R.string.source_name_ranobehub
    override val baseUrl = "https://ranobehub.org"
    override val catalogUrl = baseUrl
    override val language = LanguageCode.RUSSIAN

    private val apiBase = "https://ranobehub.org/api/"

    override suspend fun getChapterTitle(doc: Document): String? = withContext(Dispatchers.Default) {
        doc.selectFirst("h1, .chapter-title")?.text()?.takeIf { it.isNotBlank() }
    }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val indexA = doc.html().indexOf("<div class=\"title-wrapper\">")
        val indexB = doc.html().indexOf("<div class=\"ui text container\"", indexA)

        if (indexA != -1 && indexB != -1) {
            val chapterHtml = doc.html().substring(indexA, indexB)
            // Replace media IDs with proper URLs
            val processedHtml = chapterHtml.replace(
                Regex("<img data-media-id=\"(.*?)\".*?>"),
                "<img src=\"/api/media/\$1\">"
            )
            Jsoup.parse(processedHtml).body()?.let { TextExtractor.get(it) } ?: processedHtml
        } else {
            ""
        }
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect null
                val url = "${apiBase}ranobe/$id"

                val data = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonObject("data")

                data?.getAsJsonObject("posters")?.get("medium")?.asString
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect null
                val url = "${apiBase}ranobe/$id"

                val data = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonObject("data")

                data?.get("description")?.asString?.trim()
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect emptyList()
                val url = "${apiBase}ranobe/$id/contents"

                val volumes = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonArray("volumes")

                val chapters = mutableListOf<ChapterItem>()
                volumes?.forEach { volumeElement ->
                    val volume = volumeElement.asJsonObject
                    val volumeNum = volume.get("num")?.asInt ?: 0

                    volume.getAsJsonArray("chapters")?.forEach { chapterElement ->
                        val chapter = chapterElement.asJsonObject
                        val chapterNum = chapter.get("num")?.asFloat ?: 0f
                        val chapterId = chapters.size + 1

                        chapters.add(ChapterItem(
                            chapterResult = ChapterResult(
                                title = chapter.get("name")?.asString ?: "Chapter $chapterNum",
                                url = "$baseUrl/ranobe/$id/$volumeNum/$chapterNum"
                            ),
                            index = chapterId
                        ))
                    }
                }

                chapters.sortedBy { it.index }.map { it.chapterResult }
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url = "${apiBase}search?page=$page&sort=computed_rating&status=0&take=40"

                val resource = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonArray("resource")

                val books = resource?.mapNotNull { element ->
                    val novel = element.asJsonObject

                    val names = novel.getAsJsonObject("names")
                    val title = names?.get("rus")?.asString
                        ?: names?.get("eng")?.asString
                        ?: names?.get("original")?.asString

                    val id = novel.get("id")?.asString
                    val poster = novel.getAsJsonObject("poster")?.get("medium")?.asString

                    if (title != null && id != null) {
                        BookResult(
                            title = title,
                            url = "$baseUrl/ranobe/$id",
                            coverImageUrl = poster ?: ""
                        )
                    } else null
                } ?: emptyList()

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
            val url = "${apiBase}fulltext/global?query=$query&take=10"

            val results = networkClient
                .call(getRequest(url))
                .toJson()
                .asJsonArray

            val books = results?.mapNotNull { element ->
                val result = element.asJsonObject
                if (result.getAsJsonObject("meta")?.get("key")?.asString != "ranobe") return@mapNotNull null

                val data = result.getAsJsonArray("data")
                data?.mapNotNull { item ->
                    val novel = item.asJsonObject

                    val names = novel.getAsJsonObject("names")
                    val title = names?.get("rus")?.asString
                        ?: names?.get("eng")?.asString
                        ?: names?.get("original")?.asString
                        ?: novel.get("name")?.asString

                    val id = novel.get("id")?.asString
                    val image = novel.get("image")?.asString?.replace("/small", "/medium")

                    if (title != null && id != null) {
                        BookResult(
                            title = title,
                            url = "$baseUrl/ranobe/$id",
                            coverImageUrl = image ?: ""
                        )
                    } else null
                }
            }?.flatten() ?: emptyList()

            PagedList(
                list = books,
                index = index,
                isLastPage = true
            )
        }
    }

    private fun extractId(url: String): String? =
        url.removePrefix(baseUrl).trim('/').split("/").getOrNull(1)

    private data class ChapterItem(
        val chapterResult: ChapterResult,
        val index: Int,
    )
}
