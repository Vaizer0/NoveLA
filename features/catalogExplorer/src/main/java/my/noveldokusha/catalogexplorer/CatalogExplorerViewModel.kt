package my.noveldokusha.catalogexplorer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.getLanguageDisplayName
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.ScraperRepository
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.SortOrder
import my.noveldokusha.core.utils.toState
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.data.CatalogItem
import javax.inject.Inject

@Immutable
internal data class CatalogExplorerUiState(
    val selectedTabIndex: Int = 0,
    val databaseList: List<my.noveldokusha.scraper.DatabaseInterface> = emptyList(),
    val sourcesList: List<CatalogItem> = emptyList(),
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val selectedLanguages: Set<String> = emptySet(),
    val showAddByUrlDialog: Boolean = false,
)

@HiltViewModel
internal class CatalogExplorerViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val appRepository: AppRepository,
    private val appScope: AppCoroutineScope,
    val scraperRepository: ScraperRepository,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(CatalogExplorerUiState(
        databaseList = scraperRepository.databaseList(),
        selectedLanguages = appPreferences.SOURCES_LANGUAGES_ISO639_1.value
    ))
    val uiState: StateFlow<CatalogExplorerUiState> = _uiState.asStateFlow()

    // Reactive list of available languages derived from sourcesList
    private val _availableLanguages = MutableStateFlow<List<SourceLanguage>>(emptyList())
    val availableLanguages: StateFlow<List<SourceLanguage>> = _availableLanguages.asStateFlow()

    init {
        // Sync with preferences and repository
        viewModelScope.launch {
            launch {
                scraperRepository.sourcesCatalogListFlow().collectLatest { sources ->
                    _uiState.update { it.copy(sourcesList = sources) }
                }
            }
            launch {
                appPreferences.SOURCE_SORT_ORDER.flow().collect { order ->
                    _uiState.update { it.copy(sortOrder = order) }
                }
            }
            launch {
                appPreferences.SOURCES_LANGUAGES_ISO639_1.flow().collect { langs ->
                    _uiState.update { it.copy(selectedLanguages = langs) }
                }
            }
            // Recompute availableLanguages whenever sourcesList changes
            launch {
                _uiState.collectLatest { state ->
                    _availableLanguages.value = state.sourcesList
                        .mapNotNull { it.catalog.languageTag }
                        .distinct()
                        .map { code -> SourceLanguage(code, getLanguageDisplayName(code)) }
                        .sortedBy { it.name }
                }
            }
        }
    }

    // Proxy properties for backward compatibility where needed, but using uiState source
    val selectedTabIndex get() = _uiState.value.selectedTabIndex
    val databaseList get() = _uiState.value.databaseList
    val sourcesList get() = _uiState.value.sourcesList
    val sortOrder get() = _uiState.value.sortOrder
    val selectedLanguages get() = _uiState.value.selectedLanguages

    fun setShowAddByUrlDialog(show: Boolean) {
        _uiState.update { it.copy(showAddByUrlDialog = show) }
    }

    fun setTabIndex(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    fun toggleSourceLanguage(code: String) {
        val nextLangs = if (code in _uiState.value.selectedLanguages)
            _uiState.value.selectedLanguages - code
        else
            _uiState.value.selectedLanguages + code
        appPreferences.SOURCES_LANGUAGES_ISO639_1.value = nextLangs
    }

    fun clearLanguageFilter() {
        appPreferences.SOURCES_LANGUAGES_ISO639_1.value = emptySet()
    }

    fun onSourceSetPinned(id: String, pinned: Boolean) {
        appPreferences.FINDER_SOURCES_PINNED.value = appPreferences.FINDER_SOURCES_PINNED
            .value.let { if (pinned) it.plus(id) else it.minus(id) }
    }

    fun onSortOrderChange(order: SortOrder) {
        appPreferences.SOURCE_SORT_ORDER.value = order
    }


    fun addNovelsByUrls(urls: List<String>) {
        viewModelScope.launch {
            var successCount = 0
            var errorCount = 0
            val booksNeedingTitleUpdate = mutableListOf<String>()

            // Group URLs by source to add delays for sources with 5+ books
            val urlsBySource = urls.groupBy { url ->
                scraperRepository.scraper.getCompatibleSource(url)?.id ?: "unknown"
            }

            for ((_, sourceUrls) in urlsBySource) {
                val shouldAddDelay = sourceUrls.size >= 5

                for ((index, url) in sourceUrls.withIndex()) {
                    try {
                        // Try to get title from scraper (API/HTML)
                        val title = getBookTitle(url)
                        val finalTitle = title ?: "Unknown Novel"
                        val needsTitleUpdate = title == null

                        // Add to library with basic info
                        val added = appRepository.libraryBooks.toggleBookmark(url, finalTitle)
                        if (added) {
                            successCount++
                            // Track books that need title update later
                            if (needsTitleUpdate) {
                                booksNeedingTitleUpdate.add(url)
                            }
                        }

                        // If book was added (not already in library), fetch additional data synchronously
                        if (added) {
                            try {
                                // Fetch and update cover
                                val coverUrl = getBookCover(url)
                                if (coverUrl != null) {
                                    appRepository.libraryBooks.updateCover(url, coverUrl)
                                }

                                // Fetch and update description
                                val description = getBookDescription(url)
                                if (description != null) {
                                    appRepository.libraryBooks.updateDescription(url, description)
                                }

                                // Fetch and add chapters
                                val chapters = getBookChapters(url)
                                if (chapters.isNotEmpty()) {
                                    appRepository.bookChapters.insert(chapters)
                                }
                            } catch (e: Exception) {
                                // Silently fail for additional data loading
                                e.printStackTrace()
                            }
                        }

                        // Add delay after complete book loading if needed and not the last book
                        if (shouldAddDelay && index < sourceUrls.size - 1) {
                            val delayMs = appPreferences.MASS_ADD_DELAY_MS.value
                            delay(delayMs.toLong())
                        }

                    } catch (e: Exception) {
                        // Log error but continue with other URLs
                        errorCount++
                    }
                }
            }

            // Start background update for novels that need title update
            if (booksNeedingTitleUpdate.isNotEmpty()) {
                startBackgroundUpdate(booksNeedingTitleUpdate)
            }

            // TODO: Show summary message (e.g., "Added 3 novels, 1 failed")
        }
    }

    fun addNovelByUrl(url: String) {
        addNovelsByUrls(listOf(url))
    }

    private suspend fun getBookTitle(url: String): String? {
        return try {
            appRepository.downloaderRepository.bookTitle(url).toSuccessOrNull()?.data
        } catch (e: Exception) {
            null
        }
    }

    private fun startBackgroundUpdate(urls: List<String>) {
        viewModelScope.launch {
            // Process novels sequentially with retry logic
            urls.forEach { url ->
                updateNovelWithRetry(url)
                // Small delay between novels to avoid overwhelming servers
                delay(500)
            }
        }
    }

    private suspend fun updateNovelWithRetry(url: String, maxRetries: Int = 3) {
        // Skip if update is already in progress for this URL (prevents infinite loops)
        if (isUpdateInProgress(url)) return

        setUpdateInProgress(url, true)

        try {
            var retryCount = 0
            var success = false

            while (retryCount < maxRetries && !success) {
                try {
                    // Get current book info
                    val book = appRepository.libraryBooks.get(url) ?: return

                    // Try to update title if it's "Unknown Novel"
                    if (book.title == "Unknown Novel") {
                        val newTitle = getBookTitle(url)
                        if (newTitle != null && newTitle != "Unknown Novel" && newTitle.isNotBlank()) {
                            appRepository.libraryBooks.updateTitle(url, newTitle)
                        }
                    }

                    // Try to update cover if it's empty
                    if (book.coverImageUrl.isBlank()) {
                        val coverUrl = getBookCover(url)
                        if (coverUrl != null) {
                            appRepository.libraryBooks.updateCover(url, coverUrl)
                        }
                    }

                    // Try to update description if it's empty
                    if (book.description.isBlank()) {
                        val description = getBookDescription(url)
                        if (description != null) {
                            appRepository.libraryBooks.updateDescription(url, description)
                        }
                    }

                    // Update timestamp to indicate the book was processed
                    appRepository.libraryBooks.updateLastUpdateEpochTimeMilli(url)
                    success = true

                } catch (e: Exception) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        // Exponential backoff: 1s, 2s, 4s
                        delay(1000L * (1 shl (retryCount - 1)))
                    }
                }
            }

        } finally {
            setUpdateInProgress(url, false)
        }
    }

    private suspend fun getBookCover(bookUrl: String): String? {
        return try {
            appRepository.downloaderRepository.bookCoverImageUrl(bookUrl).toSuccessOrNull()?.data
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getBookDescription(bookUrl: String): String? {
        return try {
            appRepository.downloaderRepository.bookDescription(bookUrl).toSuccessOrNull()?.data
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getBookChapters(bookUrl: String): List<Chapter> {
        return try {
            appRepository.downloaderRepository.bookChaptersList(bookUrl).toSuccessOrNull()?.data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Simple in-memory tracking to prevent duplicate updates
    private val updatesInProgress = mutableSetOf<String>()

    private fun isUpdateInProgress(url: String): Boolean = updatesInProgress.contains(url)

    private fun setUpdateInProgress(url: String, inProgress: Boolean) {
        if (inProgress) {
            updatesInProgress.add(url)
        } else {
            updatesInProgress.remove(url)
        }
    }
}

@Immutable
data class SourceLanguage(val code: String, val name: String)