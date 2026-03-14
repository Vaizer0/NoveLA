package my.noveldokusha.sourceexplorer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.coreui.components.ToolbarMode
import my.noveldokusha.coreui.states.PagedListIteratorState
import my.noveldokusha.data.AppRepository
import my.noveldokusha.mappers.mapToBookMetadata
import my.noveldokusha.core.Toasty
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.utils.StateExtra_String
import my.noveldokusha.core.utils.asMutableStateOf
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.scraper.ActiveFilters
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.scraper.SourceInterface
import timber.log.Timber
import javax.inject.Inject

interface SourceCatalogStateBundle {
    var sourceBaseUrl: String
}

@HiltViewModel
internal class SourceCatalogViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val toasty: Toasty,
    stateHandle: SavedStateHandle,
    appPreferences: AppPreferences,
    scraper: Scraper,
) : BaseViewModel(), SourceCatalogStateBundle {

    override var sourceBaseUrl by StateExtra_String(stateHandle)
    private val source = scraper.getCompatibleSourceCatalog(sourceBaseUrl)!!

    // Если источник поддерживает фильтры — храним ссылку
    private val filterableSource = source as? SourceInterface.FilterableCatalog

    // Список фильтров — загружается из Lua один раз, кэшируется здесь в UI-слое
    private val _filterList = mutableStateOf(emptyList<my.noveldokusha.scraper.LuaFilter>())

    // Активные фильтры — в памяти, сброс при пересоздании ViewModel (смена конфигурации/закрытие)
    private val _activeFilters = mutableStateOf(ActiveFilters())

    val state = SourceCatalogScreenState(
        sourceCatalogNameStrId = mutableIntStateOf(source.nameStrId),
        sourceCatalogName      = mutableStateOf(source.name),
        searchTextInput        = stateHandle.asMutableStateOf("searchTextInput") { "" },
        toolbarMode            = stateHandle.asMutableStateOf("toolbarMode") { ToolbarMode.MAIN },
        fetchIterator          = PagedListIteratorState(viewModelScope) {
            source.getCatalogList(it).mapToBookMetadata()
        },
        listLayoutMode         = appPreferences.BOOKS_LIST_LAYOUT_MODE.state(viewModelScope),
        sortOrder              = appPreferences.SOURCE_SORT_ORDER.state(viewModelScope),
        hasFilters             = filterableSource != null,
        filterList             = _filterList,
        activeFilters          = _activeFilters,
        isFilterSheetOpen      = mutableStateOf(false),
    )

    init {
        onSearchCatalog()

        // Загружаем список фильтров один раз при старте.
        // Адаптер вызывает Lua каждый раз — кэш только здесь, в UI-слое.
        // При пересоздании ViewModel (смена настроек плагина) фильтры перезагрузятся.
        if (filterableSource != null) {
            viewModelScope.launch {
                filterableSource.getFilterList()
                    .onSuccess { _filterList.value = it }
                    .onError { Timber.e(it.exception, "Failed to load filter list") }
            }
        }
    }

    fun onSearchCatalog() {
        state.fetchIterator.setFunction { source.getCatalogList(it).mapToBookMetadata() }
        state.fetchIterator.reset()
        state.fetchIterator.fetchNext()
    }

    fun onSearchText(input: String) {
        // При текстовом поиске фильтры не применяются
        state.fetchIterator.setFunction { source.getCatalogSearch(it, input).mapToBookMetadata() }
        state.fetchIterator.reset()
        state.fetchIterator.fetchNext()
    }

    /**
     * Применить выбранные фильтры.
     * Если фильтры пустые — возвращаемся к обычному каталогу getCatalogList.
     */
    fun onApplyFilters(filters: ActiveFilters) {
        _activeFilters.value = filters
        state.isFilterSheetOpen.value = false

        if (filterableSource != null && !filters.isEmpty) {
            state.fetchIterator.setFunction {
                filterableSource.getCatalogFiltered(it, filters).mapToBookMetadata()
            }
        } else {
            state.fetchIterator.setFunction { source.getCatalogList(it).mapToBookMetadata() }
        }
        state.fetchIterator.reset()
        state.fetchIterator.fetchNext()
    }

    /** Сбросить все фильтры и вернуться к обычному каталогу. */
    fun onResetFilters() {
        onApplyFilters(ActiveFilters())
    }

    fun addToLibraryToggle(book: BookMetadata) =
        viewModelScope.launch(Dispatchers.IO) {
            val isInLibrary =
                appRepository.toggleBookmark(bookUrl = book.url, bookTitle = book.title)
            val res = if (isInLibrary) R.string.added_to_library else R.string.removed_from_library
            toasty.show(res)
        }
}