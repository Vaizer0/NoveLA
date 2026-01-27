package my.noveldokusha.catalogexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import my.noveldokusha.coreui.components.CollapsibleDivider
import my.noveldokusha.navigation.NavigationRouteViewModel
import my.noveldokusha.catalogexplorer.AddByUrlDialog
import my.noveldokusha.extensions.ExtensionsScreen
import my.noveldokusha.extensions.ExtensionsManagerViewModel
import my.noveldokusha.extensions.ExtensionsLanguageFilterDropDown
import my.noveldokusha.extensions.ExtensionsScreenEvent

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
                        // Browse tab actions
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
                        if (languagesOptionsExpanded) {
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
                )

                // Tab Row - only Browse tab
                TabRow(
                    selectedTabIndex = 0,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = true,
                        onClick = { /* Only one tab, no action needed */ },
                        text = {
                            Text(
                                "Browse",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    )
                }

                CollapsibleDivider(scrollBehavior.state)
            }
        },
        content = { innerPadding ->
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
