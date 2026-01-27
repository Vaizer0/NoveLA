package my.noveldokusha.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.textPadding
import my.noveldokusha.settings.R

@Composable
internal fun SettingsGeminiTranslation(
    geminiApiKey: String,
    geminiModel: String,
    preferOnlineTranslation: Boolean,
    onGeminiApiKeyChange: (String) -> Unit,
    onGeminiModelChange: (String) -> Unit,
    onPreferOnlineChange: (Boolean) -> Unit,
) {
    var apiKeyText by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var modelText by remember(geminiModel) { mutableStateOf(geminiModel) }
    
    Column {
        Text(
            text = stringResource(R.string.translation_services),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color = ColorAccent
        )

        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.gemini_api_key),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = {
                            apiKeyText = it
                            onGeminiApiKeyChange(it)
                        },
                        label = { Text(stringResource(R.string.enter_gemini_api_keys), color = MaterialTheme.colorScheme.onSurface) },
                        placeholder = { Text(stringResource(R.string.gemini_api_keys_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.get_free_api_key) + "\n" +
                               stringResource(R.string.api_key_tip) + "\n\n" +
                               stringResource(R.string.no_api_key_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        
        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.gemini_model),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelText,
                        onValueChange = {
                            modelText = it
                            onGeminiModelChange(it)
                        },
                        label = { Text(stringResource(R.string.model_name), color = MaterialTheme.colorScheme.onSurface) },
                        placeholder = { Text(stringResource(R.string.gemini_model_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.default_gemini_model) + "\n" +
                               stringResource(R.string.model_examples) + "\n" +
                               stringResource(R.string.find_models_at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.use_gemini_api))
            },
            supportingContent = {
                Text(
                    text = if (apiKeyText.isNotBlank()) {
                        stringResource(R.string.gemini_api_description_enabled)
                    } else {
                        stringResource(R.string.gemini_api_description_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = {
                Switch(
                    checked = preferOnlineTranslation,
                    onCheckedChange = onPreferOnlineChange,
                    enabled = apiKeyText.isNotBlank()
                )
            }
        )
    }
}
