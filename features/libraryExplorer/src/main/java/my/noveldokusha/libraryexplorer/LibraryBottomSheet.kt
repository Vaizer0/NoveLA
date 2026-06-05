package my.noveldokusha.libraryexplorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import my.noveldokusha.coreui.components.PosNegCheckbox
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.appPreferences.SortDirection
import my.noveldokusha.core.utils.toToggleableState

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    model: LibraryViewModel = viewModel(),
    pageModel: LibraryPageViewModel = viewModel()
) {
    if (!visible) return

    val uiState by model.uiState.collectAsStateWithLifecycle()
    val availableGenres by pageModel.availableGenres
    val selectedGenres by pageModel.selectedGenres.collectAsState()
    // Читаем текущее значение из общего preference
    val gridColumns = uiState.gridColumns

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(top = 8.dp, bottom = 64.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // ── Размер сетки ──────────────────────────────────────────────────
            Text(
                text = stringResource(id = R.string.grid_columns),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = ColorAccent,
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Метка с текущим числом колонок слева
                Text(
                    text = gridColumns.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ColorAccent,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Slider(
                    value = gridColumns.toFloat(),
                    onValueChange = { model.setGridColumns(it.toInt()) },
                    valueRange = 2f..6f,
                    steps = 3, // шаги: 2, 3, 4, 5, 6 → 3 внутренних шага
                    colors = SliderDefaults.colors(
                        thumbColor = ColorAccent,
                        activeTrackColor = ColorAccent,
                        inactiveTrackColor = ColorAccent.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            // Подписи минимума и максимума
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp)
            ) {
                Text(
                    text = "2",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "6",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .alpha(0.4f)
            )

            // ── Сортировка ────────────────────────────────────────────────────
            Text(
                text = stringResource(id = R.string.sort),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = ColorAccent,
                style = MaterialTheme.typography.titleMedium
            )

            val sortOptions = listOf(
                LibrarySortOption.TITLE           to R.string.title,
                LibrarySortOption.UNREAD_CHAPTERS to R.string.unread_chapters,
                LibrarySortOption.LAST_READ       to R.string.last_read,
                LibrarySortOption.LAST_UPDATE     to R.string.last_update,
                LibrarySortOption.ADDED           to R.string.date_added,
            )

            sortOptions.forEach { (option, labelRes) ->
                val isSelected = uiState.sortConfig.option == option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { model.setSortOption(option) },
                        colors = RadioButtonDefaults.colors(selectedColor = ColorAccent)
                    )
                    Text(
                        text = stringResource(id = labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) ColorAccent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        IconButton(onClick = { model.sortConfigToggleDirection() }) {
                            Icon(
                                imageVector = if (uiState.sortConfig.direction == SortDirection.ASC)
                                    Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                tint = ColorAccent
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .alpha(0.4f)
            )

            // ── Фильтры ───────────────────────────────────────────────────────
            Text(
                text = stringResource(id = R.string.filter),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = ColorAccent,
                style = MaterialTheme.typography.titleMedium
            )
            PosNegCheckbox(
                text = stringResource(id = R.string.read),
                state = uiState.readFilter.toToggleableState(),
                onStateChange = { model.readFilterToggle() },
                modifier = Modifier.fillMaxWidth()
            )

            // ── Жанры — только если есть хоть один в библиотеке ──────────────
            if (availableGenres.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .alpha(0.4f)
                )
                Text(
                    text = stringResource(id = R.string.genres),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = ColorAccent,
                    style = MaterialTheme.typography.titleMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    FilterChip(
                        selected = selectedGenres.isEmpty(),
                        onClick = { pageModel.clearGenreFilters() },
                        label = { Text(stringResource(R.string.all_genres)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ColorAccent.copy(alpha = 0.15f),
                            selectedLabelColor = ColorAccent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedGenres.isEmpty(),
                            selectedBorderColor = ColorAccent,
                        )
                    )
                    availableGenres.forEach { genre ->
                        FilterChip(
                            selected = genre in selectedGenres,
                            onClick = { pageModel.toggleGenreFilter(genre) },
                            label = { Text(genre, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ColorAccent.copy(alpha = 0.15f),
                                selectedLabelColor = ColorAccent,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = genre in selectedGenres,
                                selectedBorderColor = ColorAccent,
                            )
                        )
                    }
                }
            }
        }
    }
}
