package my.noveldokusha.sourceexplorer

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import my.noveldokusha.coreui.components.ToolbarMode
import my.noveldokusha.coreui.states.PagedListIteratorState
import my.noveldokusha.core.appPreferences.ListLayoutMode
import my.noveldokusha.core.appPreferences.SortOrder
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.scraper.ActiveFilters
import my.noveldokusha.scraper.LuaFilter

internal data class SourceCatalogScreenState(
    val sourceCatalogNameStrId: State<Int>,
    val sourceCatalogName: State<String?>,
    val searchTextInput: MutableState<String>,
    val fetchIterator: PagedListIteratorState<BookMetadata>,
    val toolbarMode: MutableState<ToolbarMode>,
    val listLayoutMode: MutableState<ListLayoutMode>,
    val sortOrder: MutableState<SortOrder>,

    // Фильтры — показывать кнопку только если источник реализует FilterableCatalog
    val hasFilters: Boolean,
    // Список доступных фильтров — загружается из Lua один раз при старте ViewModel
    val filterList: State<List<LuaFilter>>,
    // Текущие активные фильтры — в памяти, сброс при пересоздании ViewModel
    val activeFilters: MutableState<ActiveFilters>,
    // Управление видимостью bottom sheet
    val isFilterSheetOpen: MutableState<Boolean>,
)