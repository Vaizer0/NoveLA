package my.noveldokusha.catalogexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.MyButton
import my.noveldokusha.data.LanguageItem
import my.noveldokusha.core.appPreferences.SortOrder

@Composable
internal fun LanguagesDropDown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    languageItemList: List<LanguageItem>,
    onSourceLanguageItemToggle: (LanguageItem) -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit
) {
    val selectedLanguages = languageItemList.filter { it.active }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
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
            Text(text = "Sort Order")
            OutlinedCard {
                MyButton(
                    text = "Name (A-Z)",
                    onClick = { onSortOrderChange(SortOrder.ASCENDING) },
                    selected = sortOrder == SortOrder.ASCENDING,
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
                    onClick = { onSortOrderChange(SortOrder.DESCENDING) },
                    selected = sortOrder == SortOrder.DESCENDING,
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

            // Languages section
            Text(text = "Language Filter")
            OutlinedCard {
                languageItemList.forEach { lang ->
                    MyButton(
                        text = stringResource(id = lang.language.nameResId),
                        onClick = { onSourceLanguageItemToggle(lang) },
                        selected = lang.active,
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
