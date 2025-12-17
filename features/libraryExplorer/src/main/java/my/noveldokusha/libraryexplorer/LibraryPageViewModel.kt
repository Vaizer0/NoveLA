package my.noveldokusha.libraryexplorer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import my.noveldoksuha.coreui.BaseViewModel
import my.noveldoksuha.data.AppRepository
import my.noveldokusha.core.Toasty
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.appPreferences.SortConfig
import my.noveldokusha.core.appPreferences.SortDirection
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.core.domain.LibraryCategory
import my.noveldokusha.core.utils.toState
import javax.inject.Inject

@HiltViewModel
internal class LibraryPageViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val preferences: AppPreferences,
    private val toasty: Toasty,

) : BaseViewModel() {
    var isPullRefreshing by mutableStateOf(false)
    val listReading by createPageList(isShowCompleted = false)
    val listCompleted by createPageList(isShowCompleted = true)

    val readingCount = createCountFlow(isShowCompleted = false)
    val completedCount = createCountFlow(isShowCompleted = true)

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
    }
}
