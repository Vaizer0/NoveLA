package my.noveldokusha.libraryexplorer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.data.AppRepository
import my.noveldokusha.core.Toasty
import my.noveldokusha.core.isLocalUri
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.appPreferences.SortConfig
import my.noveldokusha.core.appPreferences.SortDirection
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.core.domain.LibraryCategory
import my.noveldokusha.core.utils.toState
import my.noveldokusha.feature.local_database.DAOs.BookGenreDao
import my.noveldokusha.interactor.WorkersInteractions
import my.noveldokusha.scraper.Scraper
import javax.inject.Inject

@HiltViewModel
internal class LibraryPageViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val preferences: AppPreferences,
    private val toasty: Toasty,
    private val workersInteractions: WorkersInteractions,
    private val bookGenreDao: BookGenreDao,
    private val scraper: Scraper,
    @ApplicationContext private val context: Context,
) : BaseViewModel() {
    var searchQuery by mutableStateOf("")
        private set

    private val _searchQueryFlow = MutableStateFlow("")
    val searchQueryFlow = _searchQueryFlow.asStateFlow()

    // Жанры-фильтры — пустой Set означает "все жанры"
    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres = _selectedGenres.asStateFlow()

    // Все доступные жанры в библиотеке — подписываемся сразу при создании VM,
    // чтобы к моменту открытия BottomSheet данные уже были готовы
    val availableGenres = bookGenreDao.getAllLibraryGenresFlow()
        .toState(viewModelScope, emptyList())

    // Полная карта жанр → Set<bookUrl> — один запрос, живёт в памяти
    // Используется для фильтрации без дополнительных запросов к БД
    private val genreToBookUrls = bookGenreDao.getAllLibraryGenreBookUrlsFlow()
        .map { pairs ->
            pairs.groupBy({ it.genre }, { it.bookUrl })
                .mapValues { it.value.toSet() }
        }
        .toState(viewModelScope, emptyMap())

    // Shared base flow — only ONE Room query (JOIN Book+Chapter) instead of 4
    private val baseLibraryFlow = appRepository.libraryBooks
        .getBooksInLibraryWithContextFlow
        .combine(preferences.LIBRARY_FILTER_READ.flow()) { list, filterRead ->
            when (filterRead) {
                TernaryState.Active -> list.filter { it.chaptersCount == it.chaptersReadCount }
                TernaryState.Inverse -> list.filter { it.chaptersCount != it.chaptersReadCount }
                TernaryState.Inactive -> list
            }
        }.combine(_searchQueryFlow) { list, query ->
            if (query.isBlank()) list
            else {
                val q = query.trim()
                val cache = genreToBookUrls.value
                list.filter { book ->
                    book.book.title.contains(q, ignoreCase = true) ||
                            cache.any { (genre, urls) ->
                                book.book.url in urls && genre.contains(q, ignoreCase = true)
                            }
                }
            }
        }.combine(_selectedGenres) { list, selectedGenres ->
            if (selectedGenres.isEmpty()) list
            else {
                val cache = genreToBookUrls.value
                list.filter { book ->
                    selectedGenres.all { genre ->
                        book.book.url in (cache[genre] ?: emptySet())
                    }
                }
            }
        }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val listReading by createPageList(isShowCompleted = false)
    val listCompleted by createPageList(isShowCompleted = true)

    val countReading by createCountFlow(isShowCompleted = false)
    val countCompleted by createCountFlow(isShowCompleted = true)

    init {
        // Sync the mutable state with the flow
        viewModelScope.launch {
            _searchQueryFlow.collect { newQuery ->
                searchQuery = newQuery
            }
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
        _searchQueryFlow.value = query
    }

    fun toggleGenreFilter(genre: String) {
        _selectedGenres.update { current ->
            if (genre in current) current - genre else current + genre
        }
    }

    fun clearGenreFilters() {
        _selectedGenres.value = emptySet()
    }


    private fun createPageList(isShowCompleted: Boolean) = baseLibraryFlow
        .map { it.filter { book -> book.book.completed == isShowCompleted } }
        .combine(preferences.LIBRARY_SORT_CONFIG.flow()) { list, sortConfig ->
            when (sortConfig.direction) {
                SortDirection.ASC -> when (sortConfig.option) {
                    LibrarySortOption.TITLE -> list.sortedBy { it.book.title.lowercase() }
                    LibrarySortOption.UNREAD_CHAPTERS -> list.sortedBy { it.chaptersCount - it.chaptersReadCount }
                    LibrarySortOption.LAST_READ -> list.sortedBy { it.book.lastReadEpochTimeMilli }
                    LibrarySortOption.LAST_UPDATE -> list.sortedBy { it.book.lastUpdateEpochTimeMilli }
                    LibrarySortOption.ADDED -> list.sortedBy { it.book.addedToLibraryEpochTimeMilli }
                }
                SortDirection.DESC -> when (sortConfig.option) {
                    LibrarySortOption.TITLE -> list.sortedByDescending { it.book.title.lowercase() }
                    LibrarySortOption.UNREAD_CHAPTERS -> list.sortedByDescending { it.chaptersCount - it.chaptersReadCount }
                    LibrarySortOption.LAST_READ -> list.sortedByDescending { it.book.lastReadEpochTimeMilli }
                    LibrarySortOption.LAST_UPDATE -> list.sortedByDescending { it.book.lastUpdateEpochTimeMilli }
                    LibrarySortOption.ADDED -> list.sortedByDescending { it.book.addedToLibraryEpochTimeMilli }
                }
            }
        }
        .toState(viewModelScope, listOf())

    private fun createCountFlow(isShowCompleted: Boolean) = baseLibraryFlow
        .map { it.count { book -> book.book.completed == isShowCompleted } }
        .toState(viewModelScope, 0)


    // Observes WorkManager state: true while manual update is running
    val isUpdating by workersInteractions.isManualUpdateRunning()
        .toState(viewModelScope, initialValue = false)

    @Suppress("UNUSED_PARAMETER")
    fun onLibraryCategoryRefresh(libraryCategory: LibraryCategory) {
        toasty.show(R.string.updating_library_notice)
        workersInteractions.checkForLibraryUpdates(libraryCategory)
    }

    fun cancelLibraryUpdates() {
        workersInteractions.cancelLibraryUpdates()
        toasty.show(R.string.update_cancelled)
    }

    fun getSourceName(url: String): String {
        if (url.isLocalUri) return "Local"
        return scraper.getCompatibleSource(url)?.resolveName(context) ?: "Unknown Source"
    }
}