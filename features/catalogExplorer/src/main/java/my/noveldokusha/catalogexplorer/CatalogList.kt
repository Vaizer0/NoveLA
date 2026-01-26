package my.noveldokusha.catalogexplorer

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.components.ImageViewGlide
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.InternalTheme
import my.noveldokusha.coreui.theme.PreviewThemes
import my.noveldokusha.core.appPreferences.SortOrder
import my.noveldokusha.data.CatalogItem
import my.noveldokusha.scraper.DatabaseInterface
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.fixtures.fixturesCatalogList
import my.noveldokusha.scraper.fixtures.fixturesDatabaseList

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun CatalogList(
    innerPadding: PaddingValues,
    databasesList: List<DatabaseInterface>,
    sourcesList: List<CatalogItem>,
    sortOrder: SortOrder?,
    onDatabaseClick: (DatabaseInterface) -> Unit,
    onSourceClick: (SourceInterface.Catalog) -> Unit,
    onSourceSetPinned: (id: String, pinned: Boolean) -> Unit,
) {
    // Сортируем источники с учетом приоритетов, если sortOrder указан:
    // 1. Local источник всегда первым
    // 2. Закрепленные источники
    // 3. Незакрепленные источники (сортируются по алфавиту)
    val sortedSourcesList = if (sortOrder != null) {
        sourcesList.sortedWith(
            compareBy<CatalogItem> { 
                // Сначала Local источник
                if (it.catalog.id == "local_source") 0 else 1
            }.thenBy { 
                // Затем закрепленные источники
                if (it.pinned) 0 else 1 
            }.thenBy { 
                // Наконец сортировка по имени
                it.catalog.nameStrId 
            }.let { comparator ->
                if (sortOrder == SortOrder.DESCENDING) comparator.reversed() else comparator
            }
        )
    } else {
        sourcesList
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 300.dp),
        modifier = Modifier.padding(paddingValues = innerPadding)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.database),
                style = MaterialTheme.typography.titleMedium,
                color = ColorAccent,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
            )
        }

        items(databasesList) {
            ListItem(
                modifier = Modifier
                    .clickable { onDatabaseClick(it) },
                headlineContent = {
                    Text(
                        text = stringResource(id = it.nameStrId),
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.english),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    ImageViewGlide(
                        imageModel = it.iconUrl,
                        modifier = Modifier.size(28.dp),
                        error = R.drawable.default_icon
                    )
                }
            )
        }

        item {
            Text(
                text = stringResource(id = R.string.sources),
                style = MaterialTheme.typography.titleMedium,
                color = ColorAccent,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
            )
        }

        items(
            items = sortedSourcesList,
            key = { it.catalog.id }
        ) {
            ListItem(
                modifier = Modifier
                    .clickable { onSourceClick(it.catalog) }
                    .animateItemPlacement(),
                headlineContent = {
                    Text(
                        text = stringResource(id = it.catalog.nameStrId),
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                supportingContent = {
                    val langResId = it.catalog.language?.nameResId
                    if (langResId != null) Text(
                        text = stringResource(id = langResId),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingContent = {
                    val iconResId = it.catalog.iconResId
                    if (iconResId != null) {
                        ImageViewGlide(
                            imageModel = iconResId,
                            modifier = Modifier.size(28.dp),
                            error = R.drawable.default_icon
                        )
                    } else {
                        val icon = it.catalog.iconUrl
                        if (icon is ImageVector) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            ImageViewGlide(
                                imageModel = icon,
                                modifier = Modifier.size(28.dp),
                                error = R.drawable.default_icon
                            )
                        }
                    }
                },
                trailingContent = {
                    Row {
                        val catalog = it.catalog
                        if (catalog is SourceInterface.Configurable) {
                            var openConfig by rememberSaveable { mutableStateOf(false) }
                            IconButton(
                                onClick = { openConfig = !openConfig },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = stringResource(R.string.configuration),
                                )
                            }
                            if (openConfig) {
                                AlertDialog(
                                    onDismissRequest = { openConfig = false },
                                    confirmButton = {
                                        FilledTonalButton(onClick = { openConfig = !openConfig }) {
                                            Text(text = stringResource(R.string.close))
                                        }
                                    },
                                    text = { catalog.ScreenConfig() },
                                    icon = {
                                        Icon(
                                            Icons.Filled.Settings,
                                            stringResource(id = R.string.configuration),
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                )
                            }
                        }
                        IconButton(
                            onClick = { onSourceSetPinned(it.catalog.id, !it.pinned) },
                        ) {
                            AnimatedTransition(targetState = it.pinned) { pinned ->
                                Icon(
                                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = stringResource(R.string.pin_or_unpin_source),
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

@PreviewThemes
@Composable
private fun PreviewView() {
    val catalogItemsList = fixturesCatalogList().mapIndexed { index, it ->
        CatalogItem(
            catalog = it,
            pinned = index % 2 == 0,
        )
    }

    InternalTheme {
        CatalogList(
            innerPadding = PaddingValues(),
            databasesList = fixturesDatabaseList(),
            sourcesList = catalogItemsList,
            sortOrder = SortOrder.ASCENDING,
            onDatabaseClick = {},
            onSourceClick = {},
            onSourceSetPinned = { _, _ -> },
        )
    }
}
