package my.noveldokusha.scraper

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document

sealed interface SourceInterface {
    val id: String

    @get:StringRes
    val nameStrId: Int
    val baseUrl: String
    val isLocalSource: Boolean get() = true
    val requiresLogin: Boolean get() = false
    val charset: String get() = "UTF-8"

    // Transform current url to preferred url
    suspend fun transformChapterUrl(url: String): String = url


    suspend fun getChapterText(doc: Document): String? = null

    interface Base : SourceInterface
    interface Catalog : SourceInterface {
        val catalogUrl: String
        val language: LanguageCode?
        val iconUrl: Any get() = "$baseUrl/favicon.ico"
        val iconResId: Int? get() = null

        suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
            Response.Success(null)

        suspend fun getBookDescription(bookUrl: String): Response<String?> = Response.Success(null)

        suspend fun getBookTitle(bookUrl: String): Response<String?> = Response.Success(null)

        /**
         * Chapters list ordered from first one (oldest) to newest one.
         */
        suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>>
        suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>>
        suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>>

        /**
         * Get a hash representing the current chapter list state for quick change detection.
         * This makes a single request to the book page and extracts a hash from
         * the latest chapter indicator (if configured for the source).
         * Returns null if the source doesn't support this feature.
         */
        suspend fun getChapterListHash(bookUrl: String): Response<String?> = Response.Success(null)
    }

    interface Configurable {
        @Composable
        fun ScreenConfig()
    }
}
