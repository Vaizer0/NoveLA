package my.noveldokusha.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.Response
import my.noveldokusha.core.map
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.feature.local_database.tables.Chapter
import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.nodes.Document
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloaderRepository @Inject constructor(
    private val scraper: Scraper,
    private val networkClient: NetworkClient,
) {

    suspend fun bookCoverImageUrl(
        bookUrl: String,
    ): Response<String?> = withContext(Dispatchers.Default) {
        val error by lazy {
            """
			Incompatible source.

			Can't find compatible source for:
			$bookUrl
		""".trimIndent()
        }

        val scrap = scraper.getCompatibleSourceCatalog(bookUrl)
            ?: return@withContext Response.Error(error, Exception())

        my.noveldokusha.network.tryFlatConnect {
            scrap.getBookCoverImageUrl(bookUrl)
        }
    }

    suspend fun bookTitle(
        bookUrl: String,
    ): Response<String?> = withContext(Dispatchers.Default) {
        val error by lazy {
            """
			Incompatible source.

			Can't find compatible source for:
			$bookUrl
		""".trimIndent()
        }

        val scrap = scraper.getCompatibleSourceCatalog(bookUrl)
            ?: return@withContext Response.Error(error, Exception())

        val apiResponse = my.noveldokusha.network.tryFlatConnect {
            scrap.getBookTitle(bookUrl)
        }

        if (apiResponse is Response.Success && apiResponse.data != null) {
            return@withContext apiResponse
        }

        my.noveldokusha.network.tryFlatConnect {
            val doc = networkClient.get(bookUrl).use { it.toDocument() }

            val titleSelectors = listOf(
                "h1", ".novel-title", ".book-title", ".title",
                "title", ".entry-title", ".post-title"
            )

            for (selector in titleSelectors) {
                val titleElement = doc.selectFirst(selector)
                val title = titleElement?.text()?.trim()?.takeIf { it.isNotBlank() }
                if (title != null && title.length > 3) {
                    return@tryFlatConnect Response.Success(title)
                }
            }

            Response.Success(null)
        }
    }

    suspend fun bookGenres(
        bookUrl: String,
    ): Response<List<String>> = withContext(Dispatchers.Default) {
        val scrap = scraper.getCompatibleSourceCatalog(bookUrl)
            ?: return@withContext Response.Success(emptyList())

        my.noveldokusha.network.tryFlatConnect {
            scrap.getBookGenres(bookUrl)
        }
    }

    suspend fun bookDescription(
        bookUrl: String,
    ): Response<String?> = withContext(Dispatchers.Default) {
        val error by lazy {
            """
			Incompatible source.

			Can't find compatible source for:
			$bookUrl
		""".trimIndent()
        }

        val scrap = scraper.getCompatibleSourceCatalog(bookUrl)
            ?: return@withContext Response.Error(error, Exception())

        my.noveldokusha.network.tryFlatConnect {
            scrap.getBookDescription(bookUrl)
        }
    }

    suspend fun bookChapter(
        chapterUrl: String,
    ): Response<my.noveldokusha.scraper.ChapterDownload> = withContext(Dispatchers.Default) {
        // Retry с exponential backoff: до 3 попыток при временных ошибках сети
        val maxRetries = 3
        var lastError: Response<my.noveldokusha.scraper.ChapterDownload>? = null

        for (attempt in 0 until maxRetries) {
            if (attempt > 0) {
                // Exponential backoff: 1с, 2с, 4с
                val backoffMs = (1000L * (1L shl (attempt - 1))).coerceAtMost(5000L)
                android.util.Log.d(TAG, "bookChapter: retry attempt $attempt/$maxRetries for $chapterUrl, waiting ${backoffMs}ms")
                delay(backoffMs)
            }

            val result = my.noveldokusha.network.tryFlatConnect {
                val request = my.noveldokusha.network.getRequest(chapterUrl)
                // FIX: .use{} закрывает response после получения redirect url
                val realUrl = networkClient
                    .call(request, followRedirects = true)
                    .use { it.request.url.toString() }

                val error by lazy {
                    """
				Unable to load chapter from url:
				$chapterUrl

				Redirect url:
				$realUrl

				Source not supported
			""".trimIndent()
                }

                scraper.getCompatibleSource(realUrl)?.also { source ->
                    // FIX: .use{} закрывает response после парсинга документа
                    val doc = if (source.charset != null) {
                        networkClient.get(source.transformChapterUrl(realUrl)).use { it.toDocument(source.charset) }
                    } else {
                        networkClient.get(source.transformChapterUrl(realUrl)).use { it.toDocument() }
                    }
                    val data = my.noveldokusha.scraper.ChapterDownload(
                        body = source.getChapterText(doc) ?: return@also,
                        title = null
                    )
                    return@tryFlatConnect Response.Success(data)
                }

                // Fallback: heuristic extraction
                val doc = networkClient.get(realUrl).use { it.toDocument() }
                val chapter = heuristicChapterExtraction(realUrl, doc)
                when (chapter) {
                    null -> Response.Error(
                        error,
                        Exception("Unable to extract chapter data with heuristics")
                    )
                    else -> Response.Success(chapter)
                }
            }

            when (result) {
                is Response.Success -> return@withContext result
                is Response.Error -> {
                    // Проверяем, является ли ошибка временной (таймаут/сеть) — тогда retry
                    val isTransient = result.exception is SocketTimeoutException ||
                        result.message.contains("Timeout", ignoreCase = true) ||
                        result.message.contains("timeout", ignoreCase = true) ||
                        result.message.contains("connect", ignoreCase = true) ||
                        result.message.contains("connection", ignoreCase = true)

                    if (!isTransient || attempt == maxRetries - 1) {
                        // Не временная ошибка или последняя попытка — возвращаем ошибку
                        return@withContext result
                    }
                    // Временная ошибка — продолжаем retry
                    lastError = result
                }
            }
        }

        // Не должно сюда попасть, но на всякий случай
        lastError ?: Response.Error("Unknown error", Exception("Unexpected retry loop exit"))
    }

    suspend fun bookChaptersList(
        bookUrl: String,
    ): Response<List<Chapter>> = withContext(Dispatchers.Default) {
        println("DownloaderRepository: Loading chapters for book: $bookUrl")

        val error by lazy {
            """
			Incompatible source.

			Can't find compatible source for:
			$bookUrl
		""".trimIndent()
        }

        val scrap = scraper.getCompatibleSourceCatalog(bookUrl)
        if (scrap == null) {
            println("DownloaderRepository: No compatible source found for $bookUrl")
            return@withContext Response.Error(error, Exception())
        }

        println("DownloaderRepository: Found source ${scrap.id} for $bookUrl")

        my.noveldokusha.network.tryFlatConnect {
            println("DownloaderRepository: Calling getChapterList for $bookUrl")
            scrap.getChapterList(bookUrl)
        }
            .map { chapters ->
                println("DownloaderRepository: Got ${chapters.size} chapters for $bookUrl")
                chapters.mapIndexed { index, it ->
                    Chapter(
                        title = it.title,
                        url = it.url,
                        bookUrl = bookUrl,
                        position = index
                    )
                }
            }
    }

    suspend fun bookChaptersListHash(
        bookUrl: String,
    ): Response<String?> = withContext(Dispatchers.Default) {
        val error by lazy {
            """
			Incompatible source.

			Can't find compatible source for:
			$bookUrl
		""".trimIndent()
        }

        val scrap = scraper.getCompatibleSourceCatalog(bookUrl)
            ?: return@withContext Response.Error(error, Exception())

        my.noveldokusha.network.tryFlatConnect {
            scrap.getChapterListHash(bookUrl)
        }
    }

    companion object {
        private const val TAG = "DownloaderRepository"
    }
}


private fun heuristicChapterExtraction(url: String, document: Document): my.noveldokusha.scraper.ChapterDownload? {
    Readability4JExtended(url, document).parse().also { article ->
        val content = article.articleContent ?: return null
        return my.noveldokusha.scraper.ChapterDownload(
            body = TextExtractor.get(content),
            title = article.title
        )
    }
}