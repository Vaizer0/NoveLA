package my.noveldokusha.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsOpenAITranslation(
    baseUrl: String,
    apiKeys: String,
    model: String,
    onBaseUrlChange: (String) -> Unit,
    onApiKeysChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
) {
    val predefinedModels = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gpt-3.5-turbo",
        "mistral-small-latest",
        "mistral-large-latest",
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemini-2.0-flash-exp:free",
        "deepseek/deepseek-chat",
        "qwen/qwen-2.5-72b-instruct",
        "microsoft/phi-4",
    )

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
        cursorColor = MaterialTheme.colorScheme.onSurface
    )

    var baseUrlText    by remember(baseUrl)     { mutableStateOf(baseUrl) }
    var apiKeysText    by remember(apiKeys)     { mutableStateOf(apiKeys) }
    var modelText      by remember(model)       { mutableStateOf(model) }


    Column {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        // ── Base URL ──────────────────────────────────────────────────────────
        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.openai_base_url),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrlText,
                        onValueChange = { baseUrlText = it; onBaseUrlChange(it) },
                        label = {
                            Text(
                                stringResource(R.string.openai_base_url_label),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        placeholder = { Text("https://api.openai.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.openai_base_url_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        // ── API Keys ──────────────────────────────────────────────────────────
        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.openai_api_keys),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeysText,
                        onValueChange = { apiKeysText = it; onApiKeysChange(it) },
                        label = {
                            Text(
                                stringResource(R.string.openai_api_keys_label),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        placeholder = { Text(stringResource(R.string.openai_api_keys_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        colors = textFieldColors
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.openai_api_keys_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        // ── Model ─────────────────────────────────────────────────────────────
        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.openai_model),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = modelText.ifEmpty { predefinedModels.first() },
                            onValueChange = { modelText = it; onModelChange(it) },
                            label = {
                                Text(
                                    stringResource(R.string.openai_model_label),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            predefinedModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        modelText = m
                                        onModelChange(m)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.openai_model_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

    }
}