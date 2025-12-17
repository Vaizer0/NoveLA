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

class RanobeLib(
    private val networkClient: NetworkClient,
) : SourceInterface.Catalog {
    override val id = "ranobelib"
    override val nameStrId = R.string.source_name_ranobelib
    override val baseUrl = "https://ranobelib.me/"
    override val catalogUrl = baseUrl
    override val language = LanguageCode.RUSSIAN

    private val apiBase = "https://api.cdnlibs.org/api/manga/"
    private val siteId = "3"

    // Cache for cover images: slug -> coverUrl
    private val coverCache = mutableMapOf<String, String>()

    override suspend fun getChapterTitle(doc: Document): String? = withContext(Dispatchers.Default) {
        val chapterPath = parseChapterPath(doc.location()) ?: return@withContext null
        val (slug, volume, number, branchId) = chapterPath

        val apiUrl = buildString {
            append(apiBase)
            append(slug)
            append("/chapter?number=")
            append(number)
            append("&volume=")
            append(volume)
            branchId?.let {
                append("&branch_id=")
                append(it)
            }
        }

        return@withContext try {
            val responseJson = networkClient
                .call(getRequest(apiUrl, headers = withSiteHeader()))
                .toJson()
                .asJsonObject

            val data = responseJson.getAsJsonObject("data")
            val name = data?.get("name")?.asString?.takeIf { it.isNotBlank() }

            // Always include volume/chapter info, even if name exists
            buildString {
                append("Том ")
                append(volume)
                append(" Глава ")
                append(number)
                if (!name.isNullOrBlank()) {
                    append(" ")
                    append(name.trim())
                }
            }
        } catch (_: Exception) {
            // Fallback to URL-derived name
            parseChapterPath(doc.location())?.let { (_, volume, number) ->
                "Том $volume Глава $number"
            }
        }
    }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val chapterPath = parseChapterPath(doc.location()) ?: return@withContext ""
        val (slug, volume, number, branchId) = chapterPath

        val apiUrl = buildString {
            append(apiBase)
            append(slug)
            append("/chapter?number=")
            append(number)
            append("&volume=")
            append(volume)
            branchId?.let {
                append("&branch_id=")
                append(it)
            }
        }

        return@withContext try {
            val responseJson = networkClient
                .call(getRequest(apiUrl, headers = withSiteHeader()))
                .toJson()
                .asJsonObject

            val data = responseJson.getAsJsonObject("data") ?: return@withContext ""
            val contentElement = data.get("content")
            val attachments = data.getAsJsonArray("attachments")

            val rawHtml = when {
                contentElement?.isJsonObject == true &&
                    contentElement.asJsonObject.get("type")?.asString == "doc" -> {
                        val body = contentElement.asJsonObject.getAsJsonArray("content")
                        jsonToHtml(body, attachments)
                    }

                contentElement?.isJsonPrimitive == true -> contentElement.asString
                else -> ""
            }

            // Clean to readable text while preserving paragraphs.
            val cleanText = Jsoup.parse(rawHtml).body()?.let { TextExtractor.get(it) } ?: rawHtml

            // Ensure we have actual content, not just whitespace
            cleanText.trim().takeIf { it.isNotEmpty() } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val slug = extractSlug(bookUrl) ?: return@tryConnect null

                // Return cached cover from catalog search (most reliable)
                coverCache[slug]
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val slug = extractSlug(bookUrl) ?: return@tryConnect null
                val url =
                    "$apiBase$slug?fields[]=summary&fields[]=genres&fields[]=tags&fields[]=status_id"

                val data = networkClient
                    .call(getRequest(url).addHeader("Site-Id", siteId))
                    .toJson()
                    .asJsonObject
                    .getAsJsonObject("data") ?: return@tryConnect null

                val summary = data.get("summary")?.asString?.trim().orEmpty()
                val genres = mergeNames(data.getAsJsonArray("genres"), data.getAsJsonArray("tags"))

                listOf(summary, genres)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .ifBlank { null }
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val slug = extractSlug(bookUrl) ?: return@tryConnect emptyList()
                val url = "$apiBase$slug/chapters"

                val json = networkClient
                    .call(getRequest(url, headers = withSiteHeader()))
                    .toJson()
                    .asJsonObject
                val data = json.getAsJsonArray("data") ?: JsonArray()

                val chapters = data.mapNotNull { element ->
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

                    ChapterItem(
                        chapterResult = ChapterResult(
                            title = title,
                            url = "${baseUrl}ru/$slug/read/v$volume/c$number?bid=$branchId"
                        ),
                        index = index
                    )
                }.sortedBy { it.index }
                    .map { it.chapterResult }

                chapters
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url =
                    "${apiBase}?site_id[0]=$siteId&page=$page&sort_by=rating_score&sort_type=desc&chapters[min]=1"
                parseCatalogResponse(index, url)
            }
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String,
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank())
                return@tryConnect PagedList.createEmpty(index = index)

            val page = index + 1
            val url = "${apiBase}?site_id[0]=$siteId&q=$input&page=$page"
            parseCatalogResponse(index, url)
        }
    }

    private suspend fun parseCatalogResponse(index: Int, url: String): PagedList<BookResult> {
                val json = networkClient
                    .call(getRequest(url, headers = withSiteHeader()))
                    .toJson()
                    .asJsonObject

        val data = json.getAsJsonArray("data") ?: JsonArray()
        val hasNextPage = json.getAsJsonObject("meta")?.get("has_next_page")?.asBoolean ?: false

        val books = data.mapNotNull { element ->
            val obj = element.asJsonObject
            val title = obj.get("rus_name")?.asString
                ?: obj.get("eng_name")?.asString
                ?: obj.get("name")?.asString
            val slug = obj.get("slug_url")?.asString
                ?: obj.get("slug")?.asString
                ?: obj.get("id")?.asString
            val cover = obj.getAsJsonObject("cover")?.get("default")?.asString.orEmpty()

            if (title == null || slug == null) return@mapNotNull null

            // Cache the cover for later use in getBookCoverImageUrl
            if (!cover.isNullOrBlank()) {
                coverCache[slug] = proxiedImageUrl(cover)
            }

            BookResult(
                title = title,
                url = "$baseUrl$slug",
                coverImageUrl = proxiedImageUrl(cover)
            )
        }

        return PagedList(
            list = books,
            index = index,
            isLastPage = !hasNextPage
        )
    }

    private fun parseChapterPath(url: String): ChapterPath? {
        // Handle new URL format: /ru/slug/read/v1/c1?bid=123
        val clean = url.removePrefix(baseUrl).trim('/').split("/")
        if (clean.size >= 5 && clean[0] == "ru" && clean[2] == "read") {
            val slug = clean[1]
            val volumePart = clean[3] // v1
            val chapterPart = clean[4].split("?")[0] // c1
            val volume = volumePart.removePrefix("v")
            val number = chapterPart.removePrefix("c")

            // Extract bid from query parameters
            val queryParams = url.substringAfter("?", "").split("&")
            val branchId = queryParams.find { it.startsWith("bid=") }?.substringAfter("bid=")

            return ChapterPath(slug, volume, number, branchId)
        }

        // Fallback to old format for compatibility
        if (clean.size < 3) return null
        val slug = clean[0]
        val volume = clean[1]
        val number = clean[2]
        val branchId = clean.getOrNull(3)
        return ChapterPath(slug, volume, number, branchId)
    }

    private fun extractSlug(url: String): String? =
        url.removePrefix(baseUrl).trim('/').takeIf { it.isNotBlank() }

    private fun proxiedImageUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // Use an image proxy to bypass referer restrictions that cause 403 on imglib.
        // images.weserv.nl expects the URL without the scheme; add https=1 to keep TLS.
        val trimmed = raw.removePrefix("https://").removePrefix("http://")
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return "https://images.weserv.nl/?url=$encoded&https=1"
    }

    private fun withSiteHeader(): Headers =
        Headers.Builder()
            .add("Site-Id", siteId)
            .build()

    private fun mergeNames(vararg arrays: JsonArray?): String =
        arrays.asSequence()
            .filterNotNull()
            .flatMap { arr ->
                arr.mapNotNull { it.asJsonObject.get("name")?.asString }
            }
            .filter { it.isNotBlank() }
            .joinToString(", ")

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
                if (url != null) builder.append("<img src='$url'>")
            }
        } else if (attrs != null) {
            val attrList = attrs.entrySet()
                .mapNotNull { entry ->
                    val value = entry.value
                    if (value == null || value.isJsonNull) return@mapNotNull null
                    "${entry.key}=\"${value.asString}\""
                }
            if (attrList.isNotEmpty()) {
                builder.append("<img ${attrList.joinToString(" ")}>")
            }
        }

        return builder.toString()
    }

    private data class ChapterPath(
        val slug: String,
        val volume: String,
        val number: String,
        val branchId: String?,
    )

    private data class ChapterItem(
        val chapterResult: ChapterResult,
        val index: Int,
    )
}

