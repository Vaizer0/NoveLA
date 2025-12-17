package my.noveldokusha.libraryexplorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import my.noveldoksuha.coreui.components.PosNegCheckbox
import my.noveldoksuha.coreui.components.SingleChoiceToggle
import my.noveldoksuha.coreui.theme.ColorAccent
import my.noveldokusha.core.appPreferences.LibrarySortOption
import my.noveldokusha.core.appPreferences.SortDirection
import my.noveldokusha.core.utils.toToggleableState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    model: LibraryViewModel = viewModel()
) {
    if (!visible) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(top = 16.dp, bottom = 64.dp)) {
            Text(
                text = stringResource(id = R.string.filter),
                modifier = Modifier
                    .padding(8.dp)
                    .padding(horizontal = 8.dp),
                color = ColorAccent,
                style = MaterialTheme.typography.titleMedium
            )
            PosNegCheckbox(
                text = stringResource(id = R.string.read),
                state = model.readFilter.toToggleableState(),
                onStateChange = { model.readFilterToggle() },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(id = R.string.sort),
                modifier = Modifier
                    .padding(8.dp)
                    .padding(horizontal = 8.dp),
                color = ColorAccent,
                style = MaterialTheme.typography.titleMedium
            )

            // Показываем текущую опцию сортировки с возможностью переключения типа
            SingleChoiceToggle(
                text = stringResource(id = when (model.sortConfig.option) {
                    LibrarySortOption.TITLE -> R.string.title
                    LibrarySortOption.UNREAD_CHAPTERS -> R.string.unread_chapters
                    LibrarySortOption.LAST_READ -> R.string.last_read
                    LibrarySortOption.LAST_UPDATE -> R.string.last_update
                    LibrarySortOption.ADDED -> R.string.date_added
                }),
                selected = true,
                onClick = { model.sortConfigNextOption() },
                modifier = Modifier.fillMaxWidth(),
                icon = {
                    when (model.sortConfig.option) {
                        LibrarySortOption.TITLE -> Icon(imageVector = Icons.Filled.SortByAlpha, null)
                        LibrarySortOption.UNREAD_CHAPTERS -> Icon(imageVector = Icons.Filled.Sort, null)
                        LibrarySortOption.LAST_READ -> Icon(imageVector = Icons.Filled.Sort, null)
                        LibrarySortOption.LAST_UPDATE -> Icon(imageVector = Icons.Filled.Update, null)
                        LibrarySortOption.ADDED -> Icon(imageVector = Icons.Filled.Sort, null)
                    }
                }
            )

            // Показываем направление сортировки
            SingleChoiceToggle(
                text = stringResource(id = if (model.sortConfig.direction == SortDirection.ASC) R.string.ascending else R.string.descending),
                selected = true,
                onClick = { model.sortConfigToggleDirection() },
                modifier = Modifier.fillMaxWidth(),
                icon = {
                    when (model.sortConfig.direction) {
                        SortDirection.ASC -> Icon(imageVector = Icons.Filled.ArrowUpward, null)
                        SortDirection.DESC -> Icon(imageVector = Icons.Filled.ArrowDownward, null)
                    }
                }
            )
        }
    }
}
