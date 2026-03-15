package my.noveldokusha.libraryexplorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import my.noveldokusha.coreui.components.BookSettingsDialog
import my.noveldokusha.coreui.components.BookSettingsDialogState
import my.noveldokusha.coreui.components.TopAppBarSearch
import my.noveldokusha.navigation.NavigationRouteViewModel
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.core.domain.LibraryCategory
import my.noveldokusha.feature.local_database.BookWithContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navigationRouteViewModel: NavigationRouteViewModel = viewModel()
) {
    val libraryModel: LibraryViewModel = viewModel()
    val pageViewModel: LibraryPageViewModel = viewModel()

    val context by rememberUpdatedState(LocalContext.current)
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val showDropdownMenu = remember { mutableStateOf(false) }
    val lastClickTime = remember { mutableStateOf(0L) }

    // Читаем количество колонок из общего preference
    val gridColumns by libraryModel.gridColumns

    val handleBookClick = { book: BookWithContext ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime.value < 200L) {
            // Debounce - ignore click
        } else {
            lastClickTime.value = currentTime
            if (libraryModel.isSelectionMode) {
                libraryModel.toggleBookSelection(book.book.url)
            } else {
                navigationRouteViewModel.chapters(
                    context = context,
                    bookMetadata = BookMetadata(
                        title = book.book.title,
                        url = book.book.url
                    )
                ).let(context::startActivity)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        snapAnimationSpec = null,
        flingAnimationSpec = null
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (libraryModel.isSelectionMode) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    title = {
                        Text(
                            text = stringResource(R.string.selected_count, libraryModel.selectedBooks.size),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val currentBooks = pageViewModel.listReading
                                libraryModel.selectAllBooks(currentBooks)
                            }
                        ) {
                            Icon(Icons.Filled.SelectAll, stringResource(R.string.select_all))
                        }
                        IconButton(
                            onClick = { libraryModel.deleteSelectedBooks() },
                            enabled = libraryModel.selectedBooks.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                stringResource(R.string.delete),
                                tint = if (libraryModel.selectedBooks.isNotEmpty())
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(onClick = { libraryModel.toggleSelectionMode() }) {
                            Icon(Icons.Filled.CheckCircle, stringResource(R.string.cancel))
                        }
                    }
                )
            } else {
                TopAppBarSearch(
                    focusRequester = focusRequester,
                    searchTextInput = pageViewModel.searchQuery,
                    onSearchTextChange = { pageViewModel.updateSearchQuery(it) },
                    onTextDone = { },
                    onClose = {
                        pageViewModel.updateSearchQuery("")
                        focusRequester.freeFocus()
                    },
                    placeholderText = stringResource(R.string.search_here),
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier,
                    showMenuButton = true,
                    onMenuClick = { showDropdownMenu.value = true },
                    dropdownContent = {
                        androidx.compose.material3.DropdownMenuItem(
                            leadingIcon = {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Sort,
                                    stringResource(R.string.filter)
                                )
                            },
                            text = { androidx.compose.material3.Text(stringResource(R.string.filter)) },
                            onClick = {
                                showDropdownMenu.value = false
                                libraryModel.showBottomSheet = true
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            leadingIcon = {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Checklist,
                                    stringResource(R.string.select_books)
                                )
                            },
                            text = { androidx.compose.material3.Text(stringResource(R.string.select_books)) },
                            onClick = {
                                showDropdownMenu.value = false
                                libraryModel.toggleSelectionMode()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            leadingIcon = {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.FileOpen,
                                    stringResource(id = R.string.import_epub)
                                )
                            },
                            text = { androidx.compose.material3.Text(stringResource(id = R.string.import_epub)) },
                            onClick = my.noveldokusha.tooling.epub_importer.onDoImportEPUB()
                        )
                    }
                )
            }
        },
        content = { innerPadding ->
            LibraryScreenBody(
                tabs = listOf("Reading", "Completed"),
                innerPadding = innerPadding,
                topAppBarState = scrollBehavior.state,
                onBookClick = handleBookClick,
                onBookLongClick = { book ->
                    if (!libraryModel.isSelectionMode) {
                        libraryModel.bookSettingsDialogState = BookSettingsDialogState.Show(book.book)
                    } else {
                        libraryModel.toggleBookSelection(book.book.url)
                    }
                },
                gridColumns = gridColumns,
                selectedBooks = libraryModel.selectedBooks,
                isSelectionMode = libraryModel.isSelectionMode
            )
        }
    )

    // Book selected options dialog
    when (val state = libraryModel.bookSettingsDialogState) {
        is BookSettingsDialogState.Show -> {
            BookSettingsDialog(
                book = state.book,
                categories = libraryModel.getCategories(),
                onDismiss = { libraryModel.bookSettingsDialogState = BookSettingsDialogState.Hide },
                onCategorySelected = { category ->
                    libraryModel.updateBookCategory(state.book.url, category)
                },
                onDeleteNovel = { libraryModel.deleteBook(state.book.url) },
                onMarkAllChaptersRead = { libraryModel.markAllChaptersAsRead(state.book.url) },
                onMarkAllChaptersUnread = { libraryModel.markAllChaptersAsUnread(state.book.url) }
            )
        }
        else -> Unit
    }

    LibraryBottomSheet(
        visible = libraryModel.showBottomSheet,
        onDismiss = { libraryModel.showBottomSheet = false }
    )
}
