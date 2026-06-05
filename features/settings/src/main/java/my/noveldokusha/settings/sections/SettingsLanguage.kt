package my.noveldokusha.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.textPadding
import my.noveldokusha.core.appPreferences.AppLanguage
import my.noveldokusha.settings.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsLanguage(
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column {
        Text(
            text = stringResource(id = R.string.language),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color = ColorAccent
        )
        SlimListItem(
            headlineContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = language == currentLanguage,
                            onClick = { onLanguageChange(language) },
                            label = { Text(text = language.displayName) }
                        )
                    }
                }
            },
            leadingContent = {
                Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        )
    }
}
