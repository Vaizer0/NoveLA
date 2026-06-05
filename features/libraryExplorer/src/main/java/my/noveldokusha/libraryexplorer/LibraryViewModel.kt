package my.noveldokusha.libraryexplorer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.core.appPreferences.SortConfig
import my.noveldokusha.core.utils.asMutableStateOf
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.coreui.components.BookSettingsDialogState
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.ScraperRepository
import my.noveldokusha.feature.local_database.BookWithContext
import my.noveldokusha.feature.local_database.tables.Chapter
import javax.inject.Inject

@Immutable
internal data class LibraryUiState(
    val bookSettingsDialogState: BookSettingsDialogState = BookSettingsDialogState.Hide,
    val showAddByUrlDialog: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedBooks: Set<String> = emptySet(),
    val gridColumns: Int = 3,
    val showBottomSheet: Boolean = false,
    val readFilter: TernaryState = TernaryState.Inactive,
    val sortConfig: SortConfig = SortConfig.DEFAULT,
    val customCategories: List<String> = emptyList(),
)

internal sealed interface LibraryUiEffect {
    data class ShowMessage(val message: String) : LibraryUiEffect
}

@HiltViewModel
internal class LibraryViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val appRepository: AppRepository,
    private val appScope: AppCoroutineScope,
    private val scraperRepository: ScraperRepository,
    stateHandle: SavedStateHandle,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _uiEffect = Channel<LibraryUiEffect>(Channel.BUFFERED)
    val uiEffect = _uiEffect.receiveAsFlow()

    init {
        // Sync with preferences
        viewModelScope.launch {
            launch {
                appPreferences.BOOKS_GRID_COLUMNS.flow().collect { cols ->
                    _uiState.update { it.copy(gridColumns = cols) }
                }
            }
            launch {
                appPreferences.LIBRARY_FILTER_READ.flow().collect { filter ->
                    _uiState.update { it.copy(readFilter = filter) }
                }
            }
            launch {
                appPreferences.LIBRARY_SORT_CONFIG.flow().collect { config ->
                    _uiState.update { it.copy(sortConfig = config) }
                }
            }
            launch {
                appPreferences.LIBRARY_CUSTOM_CATEGORIES.flow().collect { cats ->
                    _uiState.update { it.copy(customCategories = cats) }
                }
            }
        }
        
        // Restore from state handle
        stateHandle.get<BookSettingsDialogState>("bookSettingsDialogState")?.let { restored ->
            _uiState.update { it.copy(bookSettingsDialogState = restored) }
        }
        stateHandle.get<Boolean>("showBottomSheet")?.let { restored ->
            _uiState.update { it.copy(showBottomSheet = restored) }
        }
    }

    fun setBookSettingsDialogState(state: BookSettingsDialogState) {
        _uiState.update { it.copy(bookSettingsDialogState = state) }
    }

    fun setShowAddByUrlDialog(show: Boolean) {
        _uiState.update { it.copy(showAddByUrlDialog = show) }
    }

    fun setGridColumns(columns: Int) {
        appPreferences.BOOKS_GRID_COLUMNS.value = columns.coerceIn(2, 6)
    }

    fun toggleSelectionMode() {
        _uiState.update {
            val nextMode = !it.isSelectionMode
            it.copy(
                isSelectionMode = nextMode,
                selectedBooks = if (!nextMode) emptySet() else it.selectedBooks
            )
        }
    }

    fun toggleBookSelection(bookUrl: String) {
        _uiState.update {
            val nextSelected = if (it.selectedBooks.contains(bookUrl)) {
                it.selectedBooks - bookUrl
            } else {
                it.selectedBooks + bookUrl
            }
            it.copy(selectedBooks = nextSelected)
        }
    }

    fun selectAllBooks(books: List<BookWithContext>) {
        _uiState.update { it.copy(selectedBooks = books.map { b -> b.book.url }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedBooks = emptySet()) }
    }

    fun deleteSelectedBooks() {
        viewModelScope.launch {
            val toDelete = _uiState.value.selectedBooks.toList()
            _uiState.update { it.copy(selectedBooks = emptySet(), isSelectionMode = false) }
            toDelete.forEach { bookUrl ->
                val book = appRepository.libraryBooks.get(bookUrl) ?: return@forEach
                appRepository.libraryBooks.update(book.copy(inLibrary = false))
            }
        }
    }

    fun setShowBottomSheet(show: Boolean) {
        _uiState.update { it.copy(showBottomSheet = show) }
    }

    fun readFilterToggle() {
        appPreferences.LIBRARY_FILTER_READ.value = _uiState.value.readFilter.next()
    }

    fun sortConfigToggleDirection() {
        appPreferences.LIBRARY_SORT_CONFIG.value = _uiState.value.sortConfig.toggleDirection()
    }

    fun sortConfigNextOption() {
        appPreferences.LIBRARY_SORT_CONFIG.value = _uiState.value.sortConfig.nextOption()
    }

    fun setSortOption(option: LibrarySortOption) {
        appPreferences.LIBRARY_SORT_CONFIG.value = _uiState.value.sortConfig.copy(option = option)
    }

    fun bookCompletedToggle(bookUrl: String) {
        viewModelScope.launch {
            val book = appRepository.libraryBooks.get(bookUrl) ?: return@launch
            appRepository.libraryBooks.update(book.copy(completed = !book.completed))
        }
    }

    fun deleteBook(bookUrl: String) {
        viewModelScope.launch {
            val book = appRepository.libraryBooks.get(bookUrl) ?: return@launch
            appRepository.libraryBooks.update(book.copy(inLibrary = false))
            if (_uiState.value.bookSettingsDialogState is BookSettingsDialogState.Show &&
                (_uiState.value.bookSettingsDialogState as BookSettingsDialogState.Show).book.url == bookUrl
            ) {
                _uiState.update { it.copy(bookSettingsDialogState = BookSettingsDialogState.Hide) }
            }
        }
    }

    fun markAllChaptersAsRead(bookUrl: String) {
        viewModelScope.launch {
            val chapters = appRepository.bookChapters.chapters(bookUrl)
            appRepository.bookChapters.setAsRead(chapters.map { it.url })
        }
    }

    fun markAllChaptersAsUnread(bookUrl: String) {
        viewModelScope.launch {
            val chapters = appRepository.bookChapters.chapters(bookUrl)
            appRepository.bookChapters.setAsUnread(chapters.map { it.url })
        }
    }

    fun getCategories(): List<String> = listOf("", "Completed") + _uiState.value.customCategories

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
                        if (coverUrl != null) appRepository.libraryBooks.updateCover(url, coverUrl)
                    }
                    if (book.description.isBlank()) {
                        val description = getBookDescription(url)
                        if (description != null) appRepository.libraryBooks.updateDescription(url, description)
                    }
                    appRepository.libraryBooks.updateLastUpdateEpochTimeMilli(url)
                    success = true
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount < maxRetries) delay(1000L * (1 shl (retryCount - 1)))
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

    private val updatesInProgress = mutableSetOf<String>()
    private fun isUpdateInProgress(url: String) = updatesInProgress.contains(url)
    private fun setUpdateInProgress(url: String, inProgress: Boolean) {
        if (inProgress) updatesInProgress.add(url) else updatesInProgress.remove(url)
    }
}
