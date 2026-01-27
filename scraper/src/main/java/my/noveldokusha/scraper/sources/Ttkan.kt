package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import timber.log.Timber
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document

class Ttkan(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "ttkan"
    override val nameStrId = R.string.source_name_ttkan
    override val baseUrl = "https://www.ttkan.co/"
    override val catalogUrl = "https://www.ttkan.co/novel/rank"
    override val language = LanguageCode.CHINESE
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/ttkan.png"
    override val iconResId = null

    // Declarative configuration
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Catalog selectors
        catalog = CatalogSelectors(
            item = elements(".rank_list > div:has(h2)"),
            title = text("h2").Clean(),
            url = attr("href", "a[href*='/novel/chapters/']"),
            cover = attr("transform", "")
        ),

        // Search selectors
        search = SearchSelectors(
            item = elements(".novel_cell"),
            title = text("h3").Clean(),
            url = attr("href", "a[href*='/novel/chapters/']"),
            cover = attr("transform", "")
        ),

        // Book selectors
        book = BookSelectors(
            title = text("h1").Clean(),
            cover = attr("src", ".novel_info amp-img, .novel_info img"),
            description = text(".description")
        ),

        // Chapters selectors - will be overridden by AJAX
        chapters = ChapterSelectors(
            list = elements(""), // Not used due to AJAX
            title = text("a"),
            content = text(".content")
                .removeElementsDOM(
                    "script", "style",
                    ".ads_auto_place", ".mobadsq",
                    "amp-img", "img", "svg",
                    "center",
                    "#div_content_end",
                    ".div_adhost",
                    ".trc_related_container",
                    ".div_feedback",
                    ".social_share_frame",
                    "amp-social-share",
                    "a[href*=feedback]",
                    "button",
                    ".icon", ".decoration",
                    ".next_page_links",
                    ".more_recommend",
                    "a"
                )
                .applyStandardContentTransforms(baseUrl)
        ),

        // Chapters pagination - AJAX based with JSON parsing
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
            try {
                // Extract novel ID from /novel/chapters/{novel_id}
                val novelId = bookUrl.substringAfter("/novel/chapters/").substringBefore("?").trim()

                // Use API endpoint to get all chapters
                val apiUrl = "https://www.ttkan.co/api/nq/amp_novel_chapters?language=tw&novel_id=$novelId"

                val response = networkClient.get(apiUrl)
                val jsonText = response.body.string()

                // Parse JSON response manually
                val chapters = mutableListOf<ChapterResult>()

                // Simple regex-based JSON parsing for chapter data
                val itemPattern = """"chapter_name"\s*:\s*"([^"]+)"\s*,\s*"chapter_id"\s*:\s*(\d+)""".toRegex()
                var index = 1
                itemPattern.findAll(jsonText).forEach { match ->
                    val chapterName = match.groupValues[1]
                    val chapterUrl = "https://www.ttkan.co/novel/pagea/${novelId}_${index}.html"

                    chapters.add(
                        ChapterResult(
                            title = chapterName,
                            url = chapterUrl
                        )
                    )
                    index++
                }

                chapters
            } catch (e: Exception) {
                Timber.w("Failed to load chapters for Ttkan: ${e.message}")
                emptyList()
            }
        },

        // POST search enabled
        postSearchEnabled = false, // Actually uses GET search
        postSearchUrl = null,
        postSearchDataBuilder = null,

        // URL builders
        buildCatalogUrl = { index ->
            if (index == 0) catalogUrl
            else "$catalogUrl?page=${index + 1}"
        },

        buildSearchUrl = { index, query ->
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            if (index == 0) "https://www.ttkan.co/novel/search?q=$encodedQuery"
            else "https://www.ttkan.co/novel/search?q=$encodedQuery&page=${index + 1}"
        },

        // URL transformers
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.ttkanCoverUrl()
    )

}
