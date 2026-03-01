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

    /**
     * Динамическое имя источника — используется для Lua расширений,
     * у которых нет строкового ресурса.
     * Если не null, UI должен использовать это вместо nameStrId.
     */
    val name: String? get() = null

    val baseUrl: String
    val isLocalSource: Boolean get() = true
    val requiresLogin: Boolean get() = false
    val charset: String get() = "UTF-8"

    suspend fun transformChapterUrl(url: String): String = url

    suspend fun getChapterText(doc: Document): String? = null

    interface Base : SourceInterface
    interface Catalog : SourceInterface {
        val catalogUrl: String
        val language: LanguageCode?

        // String? — иконка всегда URL-строка (из YAML) или null
        val iconUrl: String? get() = null
        val iconResId: Int? get() = null

        suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
            Response.Success(null)

        suspend fun getBookDescription(bookUrl: String): Response<String?> = Response.Success(null)

        suspend fun getBookTitle(bookUrl: String): Response<String?> = Response.Success(null)

        suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>>
        suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>>
        suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>>

        suspend fun getChapterListHash(bookUrl: String): Response<String?> = Response.Success(null)
    }

    interface Configurable {
        @Composable
        fun ScreenConfig()
    }
}