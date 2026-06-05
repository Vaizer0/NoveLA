package my.noveldokusha.libraryexplorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.components.CollapsibleDivider
import my.noveldokusha.coreui.theme.colorApp
import my.noveldokusha.core.domain.LibraryCategory
import my.noveldokusha.feature.local_database.BookWithContext

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
internal fun LibraryScreenBody(
    tabs: List<String>,
    innerPadding: PaddingValues,
    topAppBarState: TopAppBarState,
    onBookClick: (BookWithContext) -> Unit,
    onBookLongClick: (BookWithContext) -> Unit,
    // Количество колонок из общего AppPreferences
    gridColumns: Int = 3,
    selectedBooks: Set<String> = emptySet(),
    isSelectionMode: Boolean = false,
    viewModel: LibraryPageViewModel = viewModel()
) {
    val tabsSizeUpdated = rememberUpdatedState(newValue = tabs.size)

    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f,
        pageCount = { tabsSizeUpdated.value }
    )
    val scope = rememberCoroutineScope()
    val updateCompleted = rememberUpdatedState(newValue = pagerState.currentPage)
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = viewModel.isPullRefreshing,
        onRefresh = {
            viewModel.onLibraryCategoryRefresh(
                libraryCategory = when (updateCompleted.value) {
                    0 -> LibraryCategory.DEFAULT
                    else -> LibraryCategory.COMPLETED
                }
            )
        },
        state = pullToRefreshState,
        modifier = Modifier.padding(innerPadding)
    ) {
        Column {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                indicator = {
                    val tabPos = it[pagerState.currentPage]
                    Box(
                        modifier = Modifier
                            .tabIndicatorOffset(tabPos)
                            .fillMaxSize()
                            .padding(4.dp)
                            .background(MaterialTheme.colorScheme.surface, my.noveldokusha.coreui.theme.shapes.small)
                            .zIndex(-1f)
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
                divider = {},
                tabs = {
                    tabs.forEachIndexed { index, text ->
                        val selected by remember { derivedStateOf { pagerState.currentPage == index } }
                        val count = when (index) {
                            0 -> viewModel.countReading
                            else -> viewModel.countCompleted
                        }
                        Tab(
                            selected = selected,
                            text = {
                                Text(
                                    text = "$text ($count)",
                                    color = if (selected) MaterialTheme.colorScheme.onBackground else my.noveldokusha.coreui.theme.SubTextLight,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } }
                        )
                    }
                }
            )
            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.Top,
            ) { page ->
                val showCompleted by remember {
                    derivedStateOf {
                        tabs[page] == "Completed"
                    }
                }
                val list: List<BookWithContext> by remember {
                    derivedStateOf {
                        when (showCompleted) {
                            true -> viewModel.listCompleted
                            else -> viewModel.listReading
                        }
                    }
                }
                val gridState = rememberLazyGridState()
                LibraryPageBody(
                    list = list,
                    onClick = onBookClick,
                    onLongClick = onBookLongClick,
                    getSourceName = { viewModel.getSourceName(it) },
                    gridColumns = gridColumns,
                    selectedBooks = selectedBooks,
                    isSelectionMode = isSelectionMode,
                    gridState = gridState,
                )
            }
        }
    }
}
