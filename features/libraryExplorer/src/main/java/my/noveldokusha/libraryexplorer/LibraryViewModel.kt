package my.noveldokusha.libraryexplorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.coreui.components.BookSettingsDialogState
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.ScraperRepository
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.utils.asMutableStateOf
import my.noveldokusha.feature.local_database.BookWithContext
import my.noveldokusha.feature.local_database.tables.Chapter
import javax.inject.Inject

@HiltViewModel
internal class LibraryViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val appRepository: AppRepository,
    private val appScope: AppCoroutineScope,
    private val scraperRepository: ScraperRepository,
    stateHandle: SavedStateHandle,
) : BaseViewModel() {
    var bookSettingsDialogState by stateHandle.asMutableStateOf<BookSettingsDialogState>(
        key = "bookSettingsDialogState",
        default = { BookSettingsDialogState.Hide },
    )

    var showAddByUrlDialog by mutableStateOf(false)

    // Selection mode
    var isSelectionMode by mutableStateOf(false)
    var selectedBooks by mutableStateOf<Set<String>>(emptySet())

    fun toggleSelectionMode() {
        isSelectionMode = !isSelectionMode
        if (!isSelectionMode) {
            selectedBooks = emptySet()
        }
    }

    fun toggleBookSelection(bookUrl: String) {
        selectedBooks = if (selectedBooks.contains(bookUrl)) {
            selectedBooks - bookUrl
        } else {
            selectedBooks + bookUrl
        }
    }

    fun selectAllBooks(books: List<BookWithContext>) {
        selectedBooks = books.map { it.book.url }.toSet()
    }

    fun clearSelection() {
        selectedBooks = emptySet()
    }

    /**
     * Removes selected books from library (sets inLibrary=false).
     * Data is kept in DB and cleaned up separately (e.g. via Settings → Clean database).
     */
    fun deleteSelectedBooks() {
        viewModelScope.launch {
            val toDelete = selectedBooks.toList()
            selectedBooks = emptySet()
            isSelectionMode = false
            toDelete.forEach { bookUrl ->
                val book = appRepository.libraryBooks.get(bookUrl) ?: return@forEach
                appRepository.libraryBooks.update(book.copy(inLibrary = false))
            }
        }
    }

    var showBottomSheet by stateHandle.asMutableStateOf("showBottomSheet") { false }

    var readFilter by appPreferences.LIBRARY_FILTER_READ.state(viewModelScope)
    var sortConfig by appPreferences.LIBRARY_SORT_CONFIG.state(viewModelScope)

    fun readFilterToggle() {
        readFilter = readFilter.next()
    }

    fun sortConfigToggleDirection() {
        sortConfig = sortConfig.toggleDirection()
    }

    fun sortConfigNextOption() {
        sortConfig = sortConfig.nextOption()
    }

    fun setSortOption(option: LibrarySortOption) {
        sortConfig = sortConfig.copy(option = option)
    }

    fun bookCompletedToggle(bookUrl: String) {
        viewModelScope.launch {
            val book = appRepository.libraryBooks.get(bookUrl) ?: return@launch
            appRepository.libraryBooks.update(book.copy(completed = !book.completed))
        }
    }

    /**
     * Removes a book from library (sets inLibrary=false).
     * Data is kept in DB and cleaned up separately (e.g. via Settings → Clean database).
     */
    fun deleteBook(bookUrl: String) {
        viewModelScope.launch {
            val book = appRepository.libraryBooks.get(bookUrl) ?: return@launch
            appRepository.libraryBooks.update(book.copy(inLibrary = false))
            if (bookSettingsDialogState is BookSettingsDialogState.Show &&
                (bookSettingsDialogState as BookSettingsDialogState.Show).book.url == bookUrl
            ) {
                bookSettingsDialogState = BookSettingsDialogState.Hide
            }
        }
    }

    fun markAllChaptersAsRead(bookUrl: String) {
        viewModelScope.launch {
            val chapters = appRepository.bookChapters.chapters(bookUrl)
            val chapterUrls = chapters.map { it.url }
            appRepository.bookChapters.setAsRead(chapterUrls)
        }
    }

    fun markAllChaptersAsUnread(bookUrl: String) {
        viewModelScope.launch {
            val chapters = appRepository.bookChapters.chapters(bookUrl)
            val chapterUrls = chapters.map { it.url }
            appRepository.bookChapters.setAsUnread(chapterUrls)
        }
    }

    // Category management
    var customCategories by appPreferences.LIBRARY_CUSTOM_CATEGORIES.state(viewModelScope)

    fun getCategories(): List<String> {
        return listOf("", "Completed") + customCategories
    }

    fun updateBookCategory(bookUrl: String, category: String) {
        viewModelScope.launch {
            appRepository.libraryBooks.updateCategory(bookUrl, category)
            val isCompleted = category == "Completed"
            val book = appRepository.libraryBooks.get(bookUrl) ?: return@launch
            if (book.completed != isCompleted) {
                appRepository.libraryBooks.update(book.copy(completed = isCompleted))
            }
        }
    }

    fun getBook(bookUrl: String) = appRepository.libraryBooks.getFlow(bookUrl).filterNotNull()

    private fun startBackgroundUpdate(urls: List<String>) {
        viewModelScope.launch {
            urls.forEach { url ->
                updateNovelWithRetry(url)
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private suspend fun updateNovelWithRetry(url: String, maxRetries: Int = 3) {
        if (isUpdateInProgress(url)) return
        setUpdateInProgress(url, true)
        try {
            var retryCount = 0
            var success = false
            while (retryCount < maxRetries && !success) {
                try {
                    val book = appRepository.libraryBooks.get(url) ?: return
                    if (book.title == "Unknown Novel" || book.title.isBlank()) {
                        val newTitle = getBookTitle(url)
                        if (newTitle != null && newTitle != "Unknown Novel" && newTitle.isNotBlank()) {
                            appRepository.libraryBooks.updateTitle(url, newTitle)
                        }
                    }
                    if (book.coverImageUrl.isBlank()) {
                        val coverUrl = getBookCover(url)
                        if (coverUrl != null) {
                            appRepository.libraryBooks.updateCover(url, coverUrl)
                        }
                    }
                    if (book.description.isBlank()) {
                        val description = getBookDescription(url)
                        if (description != null) {
                            appRepository.libraryBooks.updateDescription(url, description)
                        }
                    }
                    appRepository.libraryBooks.updateLastUpdateEpochTimeMilli(url)
                    success = true
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(1000L * (1 shl (retryCount - 1)))
                    }
                }
            }
        } finally {
            setUpdateInProgress(url, false)
        }
    }

    private suspend fun getBookTitle(bookUrl: String): String? =
        appRepository.downloaderRepository.bookTitle(bookUrl).toSuccessOrNull()?.data

    private suspend fun getBookCover(bookUrl: String): String? =
        appRepository.downloaderRepository.bookCoverImageUrl(bookUrl).toSuccessOrNull()?.data

    private suspend fun getBookDescription(bookUrl: String): String? =
        appRepository.downloaderRepository.bookDescription(bookUrl).toSuccessOrNull()?.data

    @Suppress("unused")
    private suspend fun getBookChapters(bookUrl: String): List<Chapter> =
        appRepository.downloaderRepository.bookChaptersList(bookUrl).toSuccessOrNull()?.data
            ?: emptyList()

    private val updatesInProgress = mutableSetOf<String>()
    private fun isUpdateInProgress(url: String) = updatesInProgress.contains(url)
    private fun setUpdateInProgress(url: String, inProgress: Boolean) {
        if (inProgress) updatesInProgress.add(url) else updatesInProgress.remove(url)
    }
}