package my.noveldokusha.catalogexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import my.noveldokusha.coreui.components.MyButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import my.noveldokusha.coreui.components.CollapsibleDivider
import my.noveldokusha.coreui.theme.colorApp
import my.noveldokusha.navigation.NavigationRouteViewModel
import my.noveldokusha.catalogexplorer.AddByUrlDialog
import my.noveldokusha.extensions.ExtensionsScreen
import my.noveldokusha.extensions.ExtensionsManagerViewModel
import my.noveldokusha.extensions.ExtensionsLanguageFilterDropDown
import my.noveldokusha.extensions.ExtensionsScreenEvent
import my.noveldokusha.core.appPreferences.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogExplorerScreen(
    navigationRouteViewModel: NavigationRouteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val viewModel: CatalogExplorerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    val context by rememberUpdatedState(newValue = LocalContext.current)
    var languagesOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        snapAnimationSpec = null,
        flingAnimationSpec = null
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    title = {
                        Text(
                            text = "Finder",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    actions = {
                        // Show different actions based on selected tab
                        if (viewModel.selectedTabIndex == 0) {
                            // Browse tab actions
                            Row {
                                IconButton(onClick = { viewModel.showAddByUrlDialog = true }) {
                                    Icon(
                                        Icons.Filled.AddLink,
                                        contentDescription = "Add by URL"
                                    )
                                }
                                IconButton(onClick = {
                                    navigationRouteViewModel.globalSearch(
                                        context,
                                        text = ""
                                    ).let(context::startActivity)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = "Search"
                                    )
                                }

                                // Language filter button
                                Box {
                                    IconButton(onClick = {
                                        languagesOptionsExpanded = !languagesOptionsExpanded
                                    }) {
                                        Icon(
                                            painter = painterResource(id = my.noveldokusha.coreui.R.drawable.ic_baseline_languages_24),
                                            contentDescription = "Languages",
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    // Show dropdown for Browse tab
                                    LanguagesDropDown(
                                        expanded = languagesOptionsExpanded,
                                        languageItemList = viewModel.languagesList,
                                        onDismiss = { languagesOptionsExpanded = false },
                                        onSourceLanguageItemToggle = { viewModel.toggleSourceLanguage(it.language) },
                                        sortOrder = viewModel.sortOrder,
                                        onSortOrderChange = viewModel::onSortOrderChange
                                    )
                                }
                            }
                        } else {
                            // Extensions tab actions
                            val extensionsViewModel = hiltViewModel<ExtensionsManagerViewModel>()
                            val extensionsState by extensionsViewModel.state.collectAsStateWithLifecycle()
                            var settingsExpanded by remember { mutableStateOf(false) }
                            
                            Row {
                                // Refresh button
                                IconButton(
                                    onClick = { extensionsViewModel.onEvent(ExtensionsScreenEvent.OnRefresh) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                
                                // Repository settings button
                                IconButton(
                                    onClick = { extensionsViewModel.onEvent(ExtensionsScreenEvent.OnShowRepositoryDialog) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Repository Settings",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                
                                // Sort and language filter combined
                                Box {
                                    IconButton(
                                        onClick = { settingsExpanded = !settingsExpanded }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = my.noveldokusha.coreui.R.drawable.ic_baseline_languages_24),
                                            contentDescription = "Sort and Filter",
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    
                                    // Combined dropdown with same design as LanguagesDropDown
                                    DropdownMenu(
                                        expanded = settingsExpanded,
                                        onDismissRequest = { settingsExpanded = false },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .widthIn(min = 128.dp)
                                        ) {
                                            // Sort section
                                            Text(
                                                text = "Sort Order",
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )
                                            OutlinedCard {
                                                MyButton(
                                                    text = "Name (A-Z)",
                                                    onClick = { extensionsViewModel.onEvent(ExtensionsScreenEvent.OnSortOrderChange(SortOrder.ASCENDING)) },
                                                    selected = extensionsState.sortOrder == SortOrder.ASCENDING,
                                                    selectedBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    borderWidth = Dp.Unspecified,
                                                    textAlign = TextAlign.Center,
                                                    outerPadding = 0.dp,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(0.dp),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                )
                                                MyButton(
                                                    text = "Name (Z-A)",
                                                    onClick = { extensionsViewModel.onEvent(ExtensionsScreenEvent.OnSortOrderChange(SortOrder.DESCENDING)) },
                                                    selected = extensionsState.sortOrder == SortOrder.DESCENDING,
                                                    selectedBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    borderWidth = Dp.Unspecified,
                                                    textAlign = TextAlign.Center,
                                                    outerPadding = 0.dp,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(0.dp),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                )
                                            }

                                            // Language filter section
                                            Text(
                                                text = "Language Filter",
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )
                                            OutlinedCard {
                                                // All languages option
                                                MyButton(
                                                    text = "All Languages",
                                                    onClick = {
                                                        extensionsViewModel.onEvent(ExtensionsScreenEvent.OnLanguageFilterClear(null))
                                                        settingsExpanded = false
                                                    },
                                                    selected = extensionsState.selectedLanguages.isEmpty(),
                                                    selectedBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    borderWidth = Dp.Unspecified,
                                                    textAlign = TextAlign.Center,
                                                    outerPadding = 0.dp,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(0.dp),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                )
                                                
                                                // Available languages
                                                extensionsState.availableLanguages.forEach { language ->
                                                    MyButton(
                                                        text = language.name,
                                                        onClick = {
                                                            extensionsViewModel.onEvent(ExtensionsScreenEvent.OnLanguageFilterToggle(language.code))
                                                        },
                                                        selected = language.code in extensionsState.selectedLanguages,
                                                        selectedBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                                        borderWidth = Dp.Unspecified,
                                                        textAlign = TextAlign.Center,
                                                        outerPadding = 0.dp,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = RoundedCornerShape(0.dp),
                                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )

                // Tab Row - Browse and Extensions tabs with library-style design
                TabRow(
                    selectedTabIndex = viewModel.selectedTabIndex,
                    indicator = {
                        val tabPos = it[viewModel.selectedTabIndex]
                        Box(
                            modifier = Modifier
                                .tabIndicatorOffset(tabPos)
                                .fillMaxSize()
                                .padding(6.dp)
                                .background(MaterialTheme.colorApp.tabSurface, CircleShape)
                                .zIndex(-1f)
                        )
                    },
                    divider = {
                        CollapsibleDivider(scrollBehavior.state)
                    }
                ) {
                    Tab(
                        selected = viewModel.selectedTabIndex == 0,
                        onClick = { viewModel.setTabIndex(0) },
                        text = { 
                            Text(
                                text = "Browse", 
                                color = MaterialTheme.colorScheme.onPrimary
                            ) 
                        }
                    )
                    Tab(
                        selected = viewModel.selectedTabIndex == 1,
                        onClick = { viewModel.setTabIndex(1) },
                        text = { 
                            Text(
                                text = "Extensions", 
                                color = MaterialTheme.colorScheme.onPrimary
                            ) 
                        }
                    )
                }
            }
        },
        content = { innerPadding ->
            when (viewModel.selectedTabIndex) {
                0 -> {
                    // Browse tab content
                    CatalogList(
                        innerPadding = innerPadding,
                        databasesList = viewModel.databaseList,
                        sourcesList = viewModel.sourcesList,
                        sortOrder = viewModel.sortOrder,
                        onDatabaseClick = {
                            navigationRouteViewModel.databaseSearch(
                                context,
                                databaseBaseUrl = it.baseUrl
                            ).let(context::startActivity)
                        },
                        onSourceClick = {
                            navigationRouteViewModel.sourceCatalog(
                                context,
                                sourceBaseUrl = it.baseUrl
                            ).let(context::startActivity)
                        },
                        onSourceSetPinned = viewModel::onSourceSetPinned
                    )
                }
                1 -> {
                    // Extensions tab content
                    ExtensionsScreen(
                        innerPadding = innerPadding,
                        onBackPressed = null,
                        showExtensionsLanguageFilter = false,
                        onExtensionsLanguageFilterDismiss = { },
                        onRefresh = {
                            // Extensions screen handles its own refresh
                        }
                    )
                }
                else -> {
                    // Default to Browse tab
                    CatalogList(
                        innerPadding = innerPadding,
                        databasesList = viewModel.databaseList,
                        sourcesList = viewModel.sourcesList,
                        sortOrder = viewModel.sortOrder,
                        onDatabaseClick = {
                            navigationRouteViewModel.databaseSearch(
                                context,
                                databaseBaseUrl = it.baseUrl
                            ).let(context::startActivity)
                        },
                        onSourceClick = {
                            navigationRouteViewModel.sourceCatalog(
                                context,
                                sourceBaseUrl = it.baseUrl
                            ).let(context::startActivity)
                        },
                        onSourceSetPinned = viewModel::onSourceSetPinned
                    )
                }
            }
        }
    )

    // Add by URL dialog
    if (viewModel.showAddByUrlDialog) {
        AddByUrlDialog(
            onDismiss = { viewModel.showAddByUrlDialog = false },
            onConfirm = { urls ->
                viewModel.addNovelsByUrls(urls)
                viewModel.showAddByUrlDialog = false
            },
            scraper = viewModel.scraperRepository.scraper
        )
    }
}
