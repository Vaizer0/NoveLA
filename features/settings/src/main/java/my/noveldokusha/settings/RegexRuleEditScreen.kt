package my.noveldokusha.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.settings.R

@Composable
fun RegexRuleEditScreen(
    rule: RegexRule?,
    onSave: (RegexRule) -> Unit,
    onCancel: () -> Unit,
    validationError: String?,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initialPattern = rule?.pattern ?: ""
    val initialReplacement = rule?.replacement ?: ""

    var pattern by remember { mutableStateOf(initialPattern) }
    var replacement by remember { mutableStateOf(initialReplacement) }
    var isValid by remember { mutableStateOf(true) }

    LaunchedEffect(pattern) {
        if (validationError != null) onClearError()
        isValid = try {
            if (pattern.isNotEmpty()) Regex(pattern)
            true
        } catch (e: Exception) {
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = if (initialPattern.isEmpty() && initialReplacement.isEmpty()) stringResource(id = R.string.add_new_rule) else stringResource(id = R.string.edit_rule),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Поле ввода Pattern
        OutlinedTextField(
            value = pattern,
            onValueChange = { pattern = it },
            label = { Text(stringResource(id = R.string.pattern)) },
            placeholder = { Text(stringResource(id = R.string.enter_regex_pattern)) },
            isError = !isValid && pattern.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                errorLabelColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                cursorColor = MaterialTheme.colorScheme.onSurface
            )
        )

        if (!isValid && pattern.isNotEmpty()) {
            Text(
                text = stringResource(id = R.string.invalid_regex_pattern),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Поле ввода Replacement
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text(stringResource(id = R.string.replacement)) },
            placeholder = { Text(stringResource(id = R.string.enter_replacement_text)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                errorLabelColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.onSurface
            )
        )

        if (validationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = validationError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Кнопки действий
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(id = R.string.cancel))
            }

            Button(
                onClick = {
                    if (isValid && pattern.isNotEmpty()) {
                        onSave(RegexRule(pattern, replacement))
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = isValid && pattern.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(id = R.string.save))
            }
        }
    }
}
