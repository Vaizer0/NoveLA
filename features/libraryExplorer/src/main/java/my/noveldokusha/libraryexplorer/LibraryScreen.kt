package my.noveldokusha.libraryexplorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.components.ToolbarMode
import my.noveldokusha.coreui.components.TopAppBarSearch
import my.noveldokusha.navigation.NavigationRouteViewModel
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.core.domain.LibraryCategory
import my.noveldokusha.feature.local_database.BookWithContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun LibraryScreen(
    navigationRouteViewModel: NavigationRouteViewModel = viewModel()
) {
    val libraryModel: LibraryViewModel = viewModel()
    val pageViewModel: LibraryPageViewModel = viewModel()
    
    val uiState by libraryModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val showDropdownMenu = remember { mutableStateOf(false) }
    val lastClickTime = remember { mutableStateOf(0L) }

    // Читаем количество колонок из общего preference
    val gridColumns = uiState.gridColumns

    val handleBookClick = remember(context, uiState.isSelectionMode) {
        { book: BookWithContext ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime.value >= 200L) {
                lastClickTime.value = currentTime
                if (uiState.isSelectionMode) {
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
    }

    val handleBookLongClick = remember(uiState.isSelectionMode) {
        { book: BookWithContext ->
            if (!uiState.isSelectionMode) {
                libraryModel.setBookSettingsDialogState(BookSettingsDialogState.Show(book.book))
            } else {
                libraryModel.toggleBookSelection(book.book.url)
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
            if (uiState.isSelectionMode) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    title = {
                        Text(
                            text = stringResource(R.string.selected_count, uiState.selectedBooks.size),
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
                            enabled = uiState.selectedBooks.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                stringResource(R.string.delete),
                                tint = if (uiState.selectedBooks.isNotEmpty())
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
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                ) {
                    TopAppBar(
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        title = {
                            Text(
                                text = "NoveLA", // Replaced R.string.title_library with NoveLA as per prototype
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        },
                        actions = {
                            IconButton(onClick = { libraryModel.setShowBottomSheet(true) }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.filter))
                            }
                            IconButton(onClick = { showDropdownMenu.value = true }) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.options_panel))
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = showDropdownMenu.value,
                                onDismissRequest = { showDropdownMenu.value = false }
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Filled.Checklist, stringResource(R.string.select_books))
                                    },
                                    text = { Text(stringResource(R.string.select_books)) },
                                    onClick = {
                                        showDropdownMenu.value = false
                                        libraryModel.toggleSelectionMode()
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Filled.FileOpen, stringResource(id = R.string.import_epub))
                                    },
                                    text = { Text(stringResource(id = R.string.import_epub)) },
                                    onClick = my.noveldokusha.tooling.epub_importer.onDoImportEPUB()
                                )
                            }
                        }
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        my.noveldokusha.coreui.components.MyOutlinedTextField(
                            value = pageViewModel.searchQuery,
                            onValueChange = { pageViewModel.updateSearchQuery(it) },
                            placeHolderText = stringResource(R.string.search_here),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        content = { innerPadding ->
            LibraryScreenBody(
                tabs = listOf("Reading", "Completed"),
                innerPadding = innerPadding,
                topAppBarState = scrollBehavior.state,
                onBookClick = handleBookClick,
                onBookLongClick = handleBookLongClick,
                gridColumns = gridColumns,
                selectedBooks = uiState.selectedBooks,
                isSelectionMode = uiState.isSelectionMode
            )
        }
    )

    // Handle book settings dialog
    when (val state = uiState.bookSettingsDialogState) {
        is BookSettingsDialogState.Show -> {
            BookSettingsDialog(
                book = state.book,
                onDismiss = { libraryModel.setBookSettingsDialogState(BookSettingsDialogState.Hide) },
                onDeleteNovel = { libraryModel.deleteBook(state.book.url) },
                onCategorySelected = { libraryModel.updateBookCategory(state.book.url, it) },
                categories = libraryModel.getCategories(),
                onMarkAllChaptersRead = { libraryModel.markAllChaptersAsRead(state.book.url) },
                onMarkAllChaptersUnread = { libraryModel.markAllChaptersAsUnread(state.book.url) }
            )
        }
        else -> Unit
    }

    LibraryBottomSheet(
        visible = uiState.showBottomSheet,
        onDismiss = { libraryModel.setShowBottomSheet(false) }
    )
}
