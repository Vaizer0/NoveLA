package my.noveldokusha.scraper.sources

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
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
        // For JSON API, we need to make an additional API request
        // Extract chapter URL from document location
        val chapterUrl = doc.location()
        val result = getChapterTextJson(config, chapterUrl, networkClient)
        return when (result) {
            is my.noveldokusha.core.Response.Success -> result.data ?: ""
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

    // 3. Конфигурация - ВСЯ логика API в одном месте
    private val apiBaseUrl = "https://api.cdnlibs.org/api/manga/"
    private val config = JsonApiScraperConfig(
        baseUrl = baseUrl,
        apiBaseUrl = apiBaseUrl,
        language = language,
        siteId = "3",
        headers = mapOf("Site-Id" to "3"),

        // Ключи для парсинга JSON
        catalogDataKey = "data",
        catalogTitleKeys = listOf("rus_name", "eng_name", "name"),
        catalogUrlKey = "slug",
        catalogCoverKey = "cover.default",
        catalogHasNextKey = "meta.has_next_page",

        // Поиск использует те же ключи
        searchDataKey = "data",
        searchTitleKeys = listOf("rus_name", "eng_name", "name"),
        searchUrlKey = "slug_url",
        searchCoverKey = "cover.default",
        searchHasNextKey = "meta.has_next_page",

        // POST поиск не используется
        postSearchEnabled = false,
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL билдеры для API
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

        // Парсеры данных - вся сложная логика здесь
        parseBookData = { json ->
            val data = json.getAsJsonObject("data")
            val names = data?.getAsJsonObject("names")
            val title = names?.get("rus")?.asString
                ?: names?.get("eng")?.asString
                ?: names?.get("original")?.asString
                ?: data?.get("rus_name")?.asString
                ?: data?.get("eng_name")?.asString
                ?: data?.get("name")?.asString
                ?: ""

            val cover = data?.getAsJsonObject("cover")?.get("default")?.asString
            val description = data?.get("summary")?.asString

            BookData(title, cover?.let { proxiedImageUrl(it) }, description)
        },

        parseChapterData = { json, slug ->
            if (slug == null) {
                emptyList<my.noveldokusha.scraper.domain.ChapterResult>()
            } else {
                // Try to parse as volumes structure (like RanobeHub) or data array
                val chapters = if (json.has("volumes")) {
                // Parse volumes structure
                val volumes = json.getAsJsonArray("volumes") ?: com.google.gson.JsonArray()
                val chapterItems = mutableListOf<ChapterItem>()

                volumes.forEachIndexed { volumeIndex, volumeElement ->
                    val volume = volumeElement.asJsonObject
                    val volumeNum = volume.get("num")?.asInt ?: volumeIndex + 1

                    volume.getAsJsonArray("chapters")?.forEachIndexed { chapterIndex, chapterElement ->
                        val chapter = chapterElement.asJsonObject
                        val chapterNum = chapter.get("num")?.asFloat ?: (chapterIndex + 1).toFloat()
                        val chapterId = chapterItems.size + 1

                        chapterItems.add(ChapterItem(
                            chapterResult = ChapterResult(
                                title = chapter.get("name")?.asString ?: "Chapter $chapterNum",
                                url = "${baseUrl}ru/$slug/read/v$volumeNum/c$chapterNum"
                            ),
                            index = chapterId
                        ))
                    }
                }
                chapterItems
            } else {
                // Parse data array structure (original)
                val data = json.getAsJsonArray("data") ?: com.google.gson.JsonArray()
                data.mapNotNull { element ->
                    val chapter = element.asJsonObject
                    val volume = chapter.get("volume")?.asString ?: return@mapNotNull null
                    val number = chapter.get("number")?.asString ?: return@mapNotNull null
                    val index = chapter.get("index")?.asInt ?: Int.MAX_VALUE
                    val name = chapter.get("name")?.asString?.takeIf { it.isNotBlank() }
                    val branchId = chapter
                        .getAsJsonArray("branches")
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?.get("branch_id")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?: "0"

                    val title = buildString {
                        append("Том ")
                        append(volume)
                        append(" Глава ")
                        append(number)
                        if (!name.isNullOrBlank()) {
                            append(" ")
                            append(name.trim())
                        }
                    }

                    val chapterUrl = buildString {
                        append(baseUrl)
                        append("ru/")
                        append(slug)
                        append("/read/v")
                        append(volume)
                        append("/c")
                        append(number)
                        if (branchId != "0") {
                            append("?bid=")
                            append(branchId)
                        }
                    }

                    ChapterItem(
                        chapterResult = ChapterResult(
                            title = title,
                            url = chapterUrl
                        ),
                        index = index
                    )
                }
            }.sortedBy { it.index }
                .map { it.chapterResult }

            chapters
            }
        },

        parseChapterContent = { json ->
            val data = json.getAsJsonObject("data")
            val contentElement = data?.get("content")
            val attachments = data?.getAsJsonArray("attachments")

            when {
                contentElement?.isJsonObject == true &&
                contentElement.asJsonObject.get("type")?.asString == "doc" -> {
                    val body = contentElement.asJsonObject.getAsJsonArray("content")
                    jsonToHtml(body, attachments)
                }
                contentElement?.isJsonPrimitive == true -> {
                    // Готовый HTML - нужно проксировать img src
                    val htmlContent = contentElement.asString
                    // Заменяем все img src на проксированные URL
                    htmlContent.replace(Regex("""src="([^"]+)"""")) { match ->
                        val originalUrl = match.groupValues[1]
                        val proxiedUrl = proxiedImageUrl(originalUrl)
                        """src="$proxiedUrl""""
                    }
                }
                else -> ""
            }
        }
    )

    // Вспомогательные функции для парсинга (если нужны)
    private fun proxiedImageUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.removePrefix("https://").removePrefix("http://")
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return "https://images.weserv.nl/?url=$encoded&https=1"
    }

    private fun jsonToHtml(elements: JsonArray?, attachments: JsonArray?): String {
        if (elements == null) return ""

        val attachmentMap = attachments
            ?.flatMap { attach ->
                val obj = attach.asJsonObject
                val url = obj.get("url")?.asString ?: return@flatMap emptyList()
                listOfNotNull(
                    obj.get("name")?.asString,
                    obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                ).map { key -> key to url }
            }
            ?.toMap()
            ?: emptyMap()

        val builder = StringBuilder()

        elements.forEach { element ->
            val obj = element.asJsonObject
            when (obj.get("type")?.asString) {
                "hardBreak" -> builder.append("<br>")
                "horizontalRule" -> builder.append("<hr>")
                "image" -> builder.append(renderImage(obj, attachmentMap))
                "paragraph" -> builder.append("<p>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</p>")
                "orderedList" -> builder.append("<ol>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</ol>")
                "listItem" -> builder.append("<li>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</li>")
                "blockquote" -> builder.append("<blockquote>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</blockquote>")
                "italic" -> builder.append("<i>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</i>")
                "bold" -> builder.append("<b>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</b>")
                "underline" -> builder.append("<u>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</u>")
                "heading" -> builder.append("<h2>${jsonToHtml(obj.getAsJsonArray("content"), attachments).ifBlank { "<br>" }}</h2>")
                "text" -> builder.append(obj.get("text")?.asString.orEmpty())
                else -> builder.append(obj.toString())
            }
        }

        return builder.toString()
    }

    private fun renderImage(element: JsonObject, attachments: Map<String, String>): String {
        val attrs = element.getAsJsonObject("attrs")
        val images = attrs?.getAsJsonArray("images")
        val builder = StringBuilder()

        if (images != null && images.size() > 0) {
            images.forEach { image ->
                val value = image.asJsonObject.get("image")
                val key = value?.asString ?: value?.toString()
                val url = key?.let { attachments[it] }
                if (url != null) {
                    val proxiedUrl = proxiedImageUrl(url)
                    builder.append("<img src='$proxiedUrl' onerror='this.style.display=\"none\"'>")
                }
            }
        } else if (attrs != null) {
            val attrList = attrs.entrySet()
                .mapNotNull { entry ->
                    val value = entry.value
                    if (value == null || value.isJsonNull) return@mapNotNull null
                    "${entry.key}=\"${value.asString}\""
                }
            if (attrList.isNotEmpty()) {
                builder.append("<img ${attrList.joinToString(" ")} onerror='this.style.display=\"none\"'>")
            }
        }

        return builder.toString()
    }

    private data class ChapterItem(
        val chapterResult: ChapterResult,
        val index: Int,
    )
}
