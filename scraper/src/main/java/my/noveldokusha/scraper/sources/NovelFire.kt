package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.*
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.configs.attr
import timber.log.Timber
import org.jsoup.nodes.Document

class NovelFire(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)

    override val id = "novelfire"
    override val nameStrId = R.string.source_name_novelfire
    override val baseUrl = "https://novelfire.net"
    override val catalogUrl = "$baseUrl/search-adv"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/novelfire.png"
    override val iconResId = null

    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements(".novel-list > .novel-item"),
            title = text(".novel-title").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("data-src", "img")
        ),

        search = SearchSelectors(
            item = elements(".novel-list.chapters .novel-item"),
            title = text(".novel-title").Clean(),
            url = attr("href", "a"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1.novel-title").Clean(),
            cover = attr("src", "img[src*='server-1'], .cover img"),
            description = text(".summary .content, .summary")
                .removeElementsDOM("h4.lined")
        ),

        chapters = ChapterSelectors(
            list = elements("a[href*='/chapter-']"),
            title = text("a"),
            content = text("#content, .chapter-content")
                .removeElementsDOM("script", "nav", ".ads", ".advertisement", ".disqus", ".comments")
                .applyStandardContentTransforms(baseUrl)
        ),

        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    try {
        // Get the book page to extract post_id
        val bookDoc = networkClient.get(bookUrl).toDocument()

        // Find the element with report-post_id attribute
        val reportElement = bookDoc.selectFirst("#novel-report")
        val postId = reportElement?.attr("report-post_id")

        if (postId.isNullOrEmpty()) {
            emptyList()
        } else {
            // Make AJAX request to get chapters
            val ajaxUrl = "$baseUrl/listChapterDataAjax?post_id=$postId"

            val response = networkClient.get(ajaxUrl)
            val jsonResponse = response.body.string()

            // Parse JSON response manually
            val chapters = mutableListOf<ChapterResult>()

            // Simple regex to extract chapter data from JSON
            val chapterRegex = Regex("\"n_sort\":(\\d+),\"slug\":\"([^\"]+)\",\"title\":\"([^\"]+)\"")
            chapterRegex.findAll(jsonResponse).forEach { match ->
                val chapterNumber = match.groups[1]?.value ?: return@forEach
                val slug = match.groups[2]?.value ?: return@forEach
                val title = match.groups[3]?.value?.replace("\\\"", "\"") ?: return@forEach

                chapters.add(
                    ChapterResult(
                        title = title,
                        url = "$baseUrl/book/${bookUrl.substringAfterLast("/")}/chapter-$chapterNumber"
                    )
                )
            }

            // Сортируем главы по номеру главы (от 1 до N)
            chapters.sortedBy { chapterResult ->
                // Извлекаем номер главы из URL для сортировки
                val chapterNum = Regex("chapter-(\\d+)").find(chapterResult.url)?.groupValues?.get(1)?.toIntOrNull()
                chapterNum ?: Int.MAX_VALUE // Если не удалось извлечь номер, ставим в конец
            }
        }
    } catch (e: Exception) {
        Timber.w("Failed to get chapters for NovelFire URL $bookUrl: ${e.message}")
        emptyList()
    }
},



        buildCatalogUrl = { index ->
            val page = index + 1
            "$catalogUrl?ctgcon=and&totalchapter=0&ratcon=min&rating=0&status=-1&sort=rank-top&page=$page"
        },

        buildSearchUrl = { index, query ->
            val page = index + 1
            "$baseUrl/search?keyword=$query&page=$page"
        },

        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl)
    )
}