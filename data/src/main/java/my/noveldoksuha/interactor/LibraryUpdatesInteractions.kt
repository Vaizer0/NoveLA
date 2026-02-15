package my.noveldokusha.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.DownloaderRepository
import my.noveldokusha.core.isLocalUri
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.feature.local_database.tables.Chapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryUpdatesInteractions @Inject constructor(
    private val appRepository: AppRepository,
    private val downloaderRepository: DownloaderRepository,
) {
    data class NewUpdate(
        val newChapters: List<Chapter>,
        val book: Book
    )

    data class CountingUpdating(
        val updated: Int,
        val total: Int
    )

    suspend fun updateLibraryBooks(
        completedOnes: Boolean,
        countingUpdating: MutableStateFlow<CountingUpdating?>,
        currentUpdating: MutableStateFlow<Set<Book>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ): Unit = withContext(Dispatchers.Default) {
        appRepository.libraryBooks.getAllInLibrary()
            .filter { it.completed == completedOnes }
            .filter { !it.url.isLocalUri }
            .also { list ->
                countingUpdating.update {
                    CountingUpdating(
                        updated = 0,
                        total = list.size
                    )
                }
            }
            .groupBy { it.url.toHttpUrlOrNull()?.host }
            .map { (_, books) ->
                async {
                    for (book in books) {
                        updateBook(
                            book = book,
                            currentUpdating = currentUpdating,
                            newUpdates = newUpdates,
                            failedUpdates = failedUpdates,
                            countingUpdating = countingUpdating
                        )
                    }
                }
            }
            .awaitAll()
    }


    private suspend fun updateBook(
        book: Book,
        countingUpdating: MutableStateFlow<CountingUpdating?>,
        currentUpdating: MutableStateFlow<Set<Book>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ): Unit = withContext(Dispatchers.Default) {
        currentUpdating.update { it + book }
        
        // Quick check: compare chapters list hash to detect changes
        // If hash matches stored value, skip the full update
        val shouldSkipUpdate = if (book.chaptersListHash != null) {
            downloaderRepository.bookChaptersListHash(bookUrl = book.url).let { result ->
                if (result is my.noveldokusha.core.Response.Success) {
                    result.data == book.chaptersListHash
                } else false
            }
        } else {
            // No stored hash, need to do full update
            false
        }
        
        if (shouldSkipUpdate) {
            // No changes detected, skip update
            currentUpdating.update { it - book }
            countingUpdating.update { it?.copy(updated = it.updated + 1) }
            return@withContext
        }
        
        val oldChaptersList = async(Dispatchers.IO) {
            appRepository.bookChapters.chapters(book.url).map { it.url }.toSet()
        }

        // Update book metadata if needed (title, cover, description)
        // Only update title if it's "Unknown Novel" or empty
        if (book.title == "Unknown Novel" || book.title.isBlank()) {
            downloaderRepository.bookTitle(bookUrl = book.url).onSuccess { newTitle ->
                if (!newTitle.isNullOrBlank() && newTitle != "Unknown Novel") {
                    appRepository.libraryBooks.updateTitle(book.url, newTitle)
                }
            }
        }

        // Only update cover if it's empty
        if (book.coverImageUrl.isBlank()) {
            downloaderRepository.bookCoverImageUrl(bookUrl = book.url).onSuccess { newCoverUrl ->
                if (!newCoverUrl.isNullOrBlank()) {
                    appRepository.libraryBooks.updateCover(book.url, newCoverUrl)
                }
            }
        }

        // Only update description if it's empty
        if (book.description.isBlank()) {
            downloaderRepository.bookDescription(bookUrl = book.url).onSuccess { newDescription ->
                if (!newDescription.isNullOrBlank()) {
                    appRepository.libraryBooks.updateDescription(book.url, newDescription)
                }
            }
        }

        downloaderRepository.bookChaptersList(bookUrl = book.url).onSuccess { chapters ->
            oldChaptersList.join()
            appRepository.bookChapters.merge(chapters, book.url)
            val newChapters = chapters.filter { it.url !in oldChaptersList.await() }
            if (newChapters.isNotEmpty()) {
                appRepository.libraryBooks.updateLastUpdateEpochTimeMilli(bookUrl = book.url)
                newUpdates.update { it + NewUpdate(book = book, newChapters = newChapters) }
            }
            
            // Update the chapters list hash for future change detection
            downloaderRepository.bookChaptersListHash(bookUrl = book.url).onSuccess { hash ->
                if (hash != null) {
                    appRepository.libraryBooks.updateChaptersListHash(book.url, hash)
                }
            }

        }.onError {
            failedUpdates.update { it + book }
        }
        currentUpdating.update { it - book }
        countingUpdating.update { it?.copy(updated = it.updated + 1) }
    }
}