package my.noveldokusha.historyexplorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.noveldokusha.feature.local_database.DAOs.ReadingHistoryDao
import my.noveldokusha.feature.local_database.tables.ReadingHistory
import javax.inject.Inject

data class HistoryItem(
    val bookUrl: String,
    val bookTitle: String,
    val bookCoverUrl: String,
    val lastReadChapterUrl: String?,
    val lastReadChapterTitle: String?,
    val lastReadEpochTimeMilli: Long,
    val totalChapters: Int,
    val readChapters: Int,
)

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Content(val items: List<HistoryItem>) : HistoryUiState
    data object Empty : HistoryUiState
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val readingHistoryDao: ReadingHistoryDao,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = readingHistoryDao
        .getAllFlow()
        .map { list ->
            if (list.isEmpty()) HistoryUiState.Empty
            else HistoryUiState.Content(list.map { it.toHistoryItem() })
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState.Loading
        )

    fun delete(bookUrl: String) {
        viewModelScope.launch {
            readingHistoryDao.delete(bookUrl)
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            readingHistoryDao.deleteAll()
        }
    }

    private fun ReadingHistory.toHistoryItem() = HistoryItem(
        bookUrl = bookUrl,
        bookTitle = bookTitle,
        bookCoverUrl = bookCoverUrl,
        lastReadChapterUrl = lastReadChapterUrl,
        lastReadChapterTitle = lastReadChapterTitle,
        lastReadEpochTimeMilli = lastReadEpochTimeMilli,
        totalChapters = totalChapters,
        readChapters = readChapters,
    )
}
