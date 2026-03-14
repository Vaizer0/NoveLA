package my.noveldokusha.libraryexplorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.data.AppRepository
import my.noveldokusha.core.Toasty
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.appPreferences.SortConfig
import my.noveldokusha.core.appPreferences.SortDirection
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.core.domain.LibraryCategory
import my.noveldokusha.core.utils.toState
import my.noveldokusha.feature.local_database.DAOs.BookGenreDao
import my.noveldokusha.interactor.LibraryUpdatesInteractions
import javax.inject.Inject

@HiltViewModel
internal class LibraryPageViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val preferences: AppPreferences,
    private val toasty: Toasty,
    private val libraryUpdatesInteractions: LibraryUpdatesInteractions,
    private val bookGenreDao: BookGenreDao,
) : BaseViewModel() {
    var isPullRefreshing by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
        private set

    // State flows to track update progress
    private val _countingUpdating = MutableStateFlow<LibraryUpdatesInteractions.CountingUpdating?>(null)
    val countingUpdating = _countingUpdating.asStateFlow()

    private val _currentUpdating = MutableStateFlow<Set<my.noveldokusha.feature.local_database.tables.Book>>(emptySet())
    val currentUpdating = _currentUpdating.asStateFlow()

    private val _newUpdates = MutableStateFlow<Set<LibraryUpdatesInteractions.NewUpdate>>(emptySet())
    val newUpdates = _newUpdates.asStateFlow()

    private val _failedUpdates = MutableStateFlow<Set<my.noveldokusha.feature.local_database.tables.Book>>(emptySet())
    val failedUpdates = _failedUpdates.asStateFlow()

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


    private fun createPageList(isShowCompleted: Boolean) = appRepository.libraryBooks
        .getBooksInLibraryWithContextFlow
        .map { it.filter { book -> book.book.completed == isShowCompleted } }
        .combine(preferences.LIBRARY_FILTER_READ.flow()) { list, filterRead ->
            when (filterRead) {
                TernaryState.Active -> list.filter { it.chaptersCount == it.chaptersReadCount }
                TernaryState.Inverse -> list.filter { it.chaptersCount != it.chaptersReadCount }
                TernaryState.Inactive -> list
            }
        }.combine(preferences.LIBRARY_SORT_CONFIG.flow()) { list, sortConfig ->
            val sortedList = when (sortConfig.option) {
                LibrarySortOption.TITLE -> list.sortedBy { it.book.title.lowercase() }
                LibrarySortOption.UNREAD_CHAPTERS -> list.sortedBy { it.chaptersCount - it.chaptersReadCount }
                LibrarySortOption.LAST_READ -> list.sortedBy { it.book.lastReadEpochTimeMilli }
                LibrarySortOption.LAST_UPDATE -> list.sortedBy { it.book.lastUpdateEpochTimeMilli }
                LibrarySortOption.ADDED -> list.sortedBy { it.book.addedToLibraryEpochTimeMilli }
            }

            when (sortConfig.direction) {
                SortDirection.ASC -> sortedList
                SortDirection.DESC -> sortedList.reversed()
            }
        }.combine(_searchQueryFlow) { list, query ->
            if (query.isBlank()) list else list.filter { it.book.title.contains(query, ignoreCase = true) }
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
        }
        .toState(viewModelScope, listOf())

    private fun createCountFlow(isShowCompleted: Boolean) = appRepository.libraryBooks
        .getBooksInLibraryWithContextFlow
        .map { it.filter { book -> book.book.completed == isShowCompleted } }
        .combine(preferences.LIBRARY_FILTER_READ.flow()) { list, filterRead ->
            when (filterRead) {
                TernaryState.Active -> list.filter { it.chaptersCount == it.chaptersReadCount }
                TernaryState.Inverse -> list.filter { it.chaptersCount != it.chaptersReadCount }
                TernaryState.Inactive -> list
            }
        }.map { it.size }
        .toState(viewModelScope, 0)


    private fun showLoadingSpinner() {
        viewModelScope.launch {
            // Keep for 3 seconds so the user can notice the refresh has been triggered.
            isPullRefreshing = true
            delay(3000L)
            isPullRefreshing = false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onLibraryCategoryRefresh(libraryCategory: LibraryCategory) {
        showLoadingSpinner()
        toasty.show(R.string.updating_library_notice)

        // Launch coroutine to update library books information including titles
        viewModelScope.launch {
            try {
                // Update books based on the selected category
                libraryUpdatesInteractions.updateLibraryBooks(
                    completedOnes = libraryCategory == LibraryCategory.COMPLETED,
                    countingUpdating = _countingUpdating,
                    currentUpdating = _currentUpdating,
                    newUpdates = _newUpdates,
                    failedUpdates = _failedUpdates
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}