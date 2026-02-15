package my.noveldokusha.scraper.sources

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toJson
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.configs.JsonApiScraperConfig
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.configs.BookData
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RanobeLib(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // 1. Методы интерфейса
    override suspend fun getCatalogList(index: Int) = getCatalogListJson(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearchJson(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCoverJson(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescriptionJson(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterListJson(config, bookUrl, networkClient)

    override suspend fun getChapterText(doc: Document): String {
        val chapterUrl = doc.location()
        val result = getChapterTextJson(config, chapterUrl, networkClient)
        return when (result) {
            is my.noveldokusha.core.Response.Success -> {
                val rawHtml = result.data ?: ""
                Jsoup.parse(rawHtml).body()?.let { TextExtractor.get(it) } ?: rawHtml
            }
            is my.noveldokusha.core.Response.Error -> ""
        }
    }

    // 2. Основные идентификаторы
    override val id = "ranobelib"
    override val nameStrId = R.string.source_name_ranobelib
    override val baseUrl = "https://ranobelib.me/"
    override val catalogUrl = baseUrl
    override val language = LanguageCode.RUSSIAN
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/ranobelib.png"
    override val iconResId = null

    // 3. Конфигурация
    private val apiBaseUrl = "https://api.cdnlibs.org/api/manga/"
    private val config = JsonApiScraperConfig(
        baseUrl = baseUrl,
        apiBaseUrl = apiBaseUrl,
        language = language,
        siteId = "3",
        headers = mapOf("Site-Id" to "3"),

        catalogDataKey = "data",
        catalogTitleKeys = listOf("rus_name", "eng_name", "name"),
        catalogUrlKey = "slug",
        catalogCoverKey = "cover.default",
        catalogHasNextKey = "meta.has_next_page",

        searchDataKey = "data",
        searchTitleKeys = listOf("rus_name", "eng_name", "name"),
        searchUrlKey = "slug_url",
        searchCoverKey = "cover.default",
        searchHasNextKey = "meta.has_next_page",

        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        buildCatalogUrl = { page ->
            "$apiBaseUrl?site_id[0]=3&page=$page&sort_by=rating_score&sort_type=desc&chapters[min]=1"
        },
        buildSearchUrl = { page, query ->
            "$apiBaseUrl?site_id[0]=3&page=$page&q=$query"
        },
        buildBookUrl = { slug -> "$apiBaseUrl$slug" },
        buildChapterListUrl = { slug -> "$apiBaseUrl$slug/chapters" },
        buildChapterUrl = { slug, chapter ->
            val volume = chapter["volume"] as String
            val number = chapter["number"] as String
            val branchId = chapter["branchId"] as? String
            "$baseUrl/ru/$slug/read/v$volume/c$number${if (branchId != null && branchId != "0") "?bid=$branchId" else ""}"
        },

        parseBookData = { json ->
            val data = json.getAsJsonObject("data")
            val names = data?.getAsJsonObject("names")
            val title = names?.get("rus")?.asString
                ?: names?.get("eng")?.asString
                ?: data?.get("rus_name")?.asString
                ?: data?.get("name")?.asString ?: ""

            val cover = data?.getAsJsonObject("cover")?.get("default")?.asString
            BookData(title, cover?.let { proxiedImageUrl(it) }, data?.get("summary")?.asString)
        },

        parseChapterData = { json, slug ->
            if (slug == null) emptyList() else {
                val data = json.getAsJsonArray("data") ?: com.google.gson.JsonArray()
                data.mapNotNull { element ->
                    val chapter = element.asJsonObject
                    val volume = chapter.get("volume")?.asString ?: return@mapNotNull null
                    val number = chapter.get("number")?.asString ?: return@mapNotNull null
                    val name = chapter.get("name")?.asString?.takeIf { it.isNotBlank() }
                    val bid = chapter.getAsJsonArray("branches")?.firstOrNull()?.asJsonObject?.get("branch_id")?.asString ?: "0"

                    ChapterItem(
                        ChapterResult(
                            title = "Том $volume Глава $number${if (name != null) " $name" else ""}",
                            url = "${baseUrl}ru/$slug/read/v$volume/c$number${if (bid != "0") "?bid=$bid" else ""}"
                        ),
                        index = chapter.get("index")?.asInt ?: 0
                    )
                }.sortedBy { it.index }.map { it.chapterResult }
            }
        },

        parseChapterContent = { json ->
            val data = json.getAsJsonObject("data")
            val contentElement = data?.get("content")
            val attachments = data?.getAsJsonArray("attachments")

            when {
                contentElement?.isJsonObject == true && contentElement.asJsonObject.get("type")?.asString == "doc" -> {
                    val body = contentElement.asJsonObject.getAsJsonArray("content")
                    jsonToHtml(body, attachments)
                }
                contentElement?.isJsonPrimitive == true -> {
                    contentElement.asString.replace(Regex("""src="([^"]+)"""")) { "src=\"${proxiedImageUrl(it.groupValues[1])}\"" }
                }
                else -> ""
            }
        }
    )

    private fun jsonToHtml(elements: JsonArray?, attachments: JsonArray?): String {
        if (elements == null) return ""
        val attachmentMap = attachments?.mapNotNull {
            val obj = it.asJsonObject
            val id = obj.get("id")?.asString ?: obj.get("name")?.asString ?: return@mapNotNull null
            id to (obj.get("url")?.asString ?: "")
        }?.toMap() ?: emptyMap()

        val builder = StringBuilder()
        elements.forEach { element ->
            val obj = element.asJsonObject
            val type = obj.get("type")?.asString
            val content = obj.getAsJsonArray("content")

            when (type) {
                // ВАЖНО: В RanobeLib стили (marks) находятся внутри текстового узла
                "text" -> {
                    var text = obj.get("text")?.asString.orEmpty()
                    obj.getAsJsonArray("marks")?.forEach {
                        when (it.asJsonObject.get("type")?.asString) {
                            "bold" -> text = "<b>$text</b>"
                            "italic" -> text = "<i>$text</i>"
                            "underline" -> text = "<u>$text</u>"
                        }
                    }
                    builder.append(text)
                }
                "paragraph" -> builder.append("<p>${jsonToHtml(content, attachments)}</p>")
                "heading" -> builder.append("<h2>${jsonToHtml(content, attachments)}</h2>")
                "listItem" -> builder.append("<li>${jsonToHtml(content, attachments)}</li>")
                "bulletList", "orderedList" -> builder.append("<ul>${jsonToHtml(content, attachments)}</ul>")
                "blockquote" -> builder.append("<blockquote>${jsonToHtml(content, attachments)}</blockquote>")
                "image" -> builder.append(renderImage(obj, attachmentMap))
                "hardBreak" -> builder.append("<br>")
                "horizontalRule" -> builder.append("<hr>")
                else -> if (content != null) builder.append(jsonToHtml(content, attachments))
            }
        }
        return builder.toString()
    }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val slug = bookUrl.removePrefix(baseUrl).trim('/').split("/").getOrNull(0) ?: return@tryConnect null
                val url = "${apiBaseUrl}${slug}/chapters"
                val json = networkClient.call(my.noveldokusha.network.getRequest(url)).toJson().asJsonObject
                val chapters = json.getAsJsonArray("data")
                val lastChapter = chapters?.lastOrNull()?.asJsonObject
                lastChapter?.get("item_number")?.asString
            }
        }

    private fun renderImage(element: JsonObject, attachments: Map<String, String>): String {
        val attrs = element.getAsJsonObject("attrs") ?: return ""
        val id = attrs.get("id")?.asString ?: attrs.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("id")?.asString
        val url = attachments[id] ?: attrs.get("src")?.asString
        return if (!url.isNullOrBlank()) "<img src=\"${proxiedImageUrl(url)}\">" else ""
    }

    private fun proxiedImageUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val encoded = URLEncoder.encode(raw.removePrefix("https://").removePrefix("http://"), StandardCharsets.UTF_8.name())
        return "https://images.weserv.nl/?url=$encoded&https=1"
    }

    private data class ChapterItem(val chapterResult: ChapterResult, val index: Int)
}