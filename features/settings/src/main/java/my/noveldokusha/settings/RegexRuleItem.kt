package my.noveldokusha.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.settings.R

@Composable
fun RegexRuleItem(
    rule: RegexRule,
    onEdit: (RegexRule) -> Unit,
    onDelete: (RegexRule) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${stringResource(id = R.string.pattern)}: ${rule.pattern}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${stringResource(id = R.string.replacement)}: ${rule.replacement}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { onEdit(rule) }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.edit_rule), tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { onDelete(rule) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete_rule), tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = onMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(id = R.string.move_up), tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(id = R.string.move_down), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
