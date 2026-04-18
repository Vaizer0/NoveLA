package my.noveldokusha.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.textPadding
import my.noveldokusha.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsGeminiTranslation(
    translationProvider: String,
    geminiApiKey: String,
    geminiModel: String,
    googlePaApiKeys: String,
    openAiBaseUrl: String,
    openAiApiKeys: String,
    openAiModel: String,
    activeSystemPrompt: String,
    promptPresets: List<Pair<String, String>>,
    promptUseEnglishLocale: Boolean,
    onTranslationProviderChange: (String) -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onGeminiModelChange: (String) -> Unit,
    onGooglePaApiKeysChange: (String) -> Unit,
    onOpenAiBaseUrlChange: (String) -> Unit,
    onOpenAiApiKeysChange: (String) -> Unit,
    onOpenAiModelChange: (String) -> Unit,
    onActiveSystemPromptChange: (String) -> Unit,
    onPromptUseEnglishLocaleChange: (Boolean) -> Unit,
    onSavePreset: (name: String, prompt: String) -> Unit,
    onDeletePreset: (name: String) -> Unit,
) {
    val predefinedGeminiModels = listOf(
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash-exp",
        "gemini-1.5-flash",
        "gemini-1.5-pro",
        "gemini-1.0-pro"
    )

    var apiKeyText by remember(geminiApiKey)    { mutableStateOf(geminiApiKey) }
    var modelText  by remember(geminiModel)     { mutableStateOf(geminiModel) }
    var paKeysText by remember(googlePaApiKeys) { mutableStateOf(googlePaApiKeys) }

    Column {
        Text(
            text     = stringResource(R.string.translation_services),
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color    = ColorAccent
        )

        // ── Provider picker ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            ProviderRadioOption(
                label       = stringResource(R.string.provider_google_pa),
                description = stringResource(R.string.provider_google_pa_description),
                selected    = translationProvider == "GOOGLE_PA",
                onClick     = { onTranslationProviderChange("GOOGLE_PA") }
            )
            Spacer(Modifier.height(4.dp))
            ProviderRadioOption(
                label       = stringResource(R.string.provider_google_free),
                description = stringResource(R.string.provider_google_free_description),
                selected    = translationProvider == "GOOGLE_FREE",
                onClick     = { onTranslationProviderChange("GOOGLE_FREE") }
            )
            Spacer(Modifier.height(4.dp))
            ProviderRadioOption(
                label       = stringResource(R.string.provider_gemini),
                description = stringResource(R.string.provider_gemini_description),
                selected    = translationProvider == "GEMINI",
                onClick     = { onTranslationProviderChange("GEMINI") }
            )
            Spacer(Modifier.height(4.dp))
            ProviderRadioOption(
                label       = stringResource(R.string.provider_openai),
                description = stringResource(R.string.provider_openai_description),
                selected    = translationProvider == "OPENAI",
                onClick     = { onTranslationProviderChange("OPENAI") }
            )
        }

        // ── Google PA config ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = translationProvider == "GOOGLE_PA",
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                ListItem(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier          = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text  = stringResource(R.string.google_pa_api_keys),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value         = paKeysText,
                                onValueChange = { paKeysText = it; onGooglePaApiKeysChange(it) },
                                label         = { Text(stringResource(R.string.enter_google_pa_api_keys), color = MaterialTheme.colorScheme.onSurface) },
                                placeholder   = { Text(stringResource(R.string.google_pa_api_keys_placeholder)) },
                                modifier      = Modifier.fillMaxWidth(),
                                minLines      = 2,
                                maxLines      = 5,
                                colors        = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = MaterialTheme.colorScheme.onSurface,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
                                    cursorColor          = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text  = stringResource(R.string.google_pa_api_keys_tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }

        // ── Gemini config ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = translationProvider == "GEMINI",
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                // API Key
                ListItem(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier          = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text  = stringResource(R.string.gemini_api_key),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value         = apiKeyText,
                                onValueChange = { apiKeyText = it; onGeminiApiKeyChange(it) },
                                label         = { Text(stringResource(R.string.enter_gemini_api_keys), color = MaterialTheme.colorScheme.onSurface) },
                                placeholder   = { Text(stringResource(R.string.gemini_api_keys_placeholder)) },
                                modifier      = Modifier.fillMaxWidth(),
                                minLines      = 2,
                                maxLines      = 5,
                                colors        = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = MaterialTheme.colorScheme.onSurface,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
                                    cursorColor          = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text  = stringResource(R.string.get_free_api_key) + "\n" +
                                        stringResource(R.string.api_key_tip) + "\n\n" +
                                        stringResource(R.string.no_api_key_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )

                // Model
                ListItem(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text  = stringResource(R.string.gemini_model),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(8.dp))

                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded         = expanded,
                                onExpandedChange = { expanded = it }
                            ) {
                                OutlinedTextField(
                                    value         = modelText.ifEmpty { predefinedGeminiModels.first() },
                                    onValueChange = { modelText = it; onGeminiModelChange(it) },
                                    label         = { Text(stringResource(R.string.model_name), color = MaterialTheme.colorScheme.onSurface) },
                                    trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier      = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    singleLine    = true,
                                    colors        = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor   = MaterialTheme.colorScheme.onSurface,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
                                        cursorColor          = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                ExposedDropdownMenu(
                                    expanded         = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    predefinedGeminiModels.forEach { m ->
                                        DropdownMenuItem(
                                            text    = { Text(m) },
                                            onClick = {
                                                modelText = m
                                                onGeminiModelChange(m)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Text(
                                text  = stringResource(R.string.default_gemini_model) + "\n" +
                                        stringResource(R.string.model_examples),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )

                // Prompt manager для Gemini
                SettingsPromptManager(
                    activeSystemPrompt             = activeSystemPrompt,
                    promptPresets                  = promptPresets,
                    promptUseEnglishLocale         = promptUseEnglishLocale,
                    onActiveSystemPromptChange     = onActiveSystemPromptChange,
                    onPromptUseEnglishLocaleChange = onPromptUseEnglishLocaleChange,
                    onSavePreset                   = onSavePreset,
                    onDeletePreset                 = onDeletePreset,
                )
            }
        }

        // ── OpenAI-compatible config ──────────────────────────────────────────
        AnimatedVisibility(
            visible = translationProvider == "OPENAI",
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            // Оба composable обёрнуты в Column — иначе AnimatedVisibility
            // показывает только первый дочерний элемент
            Column {
                SettingsOpenAITranslation(
                    baseUrl         = openAiBaseUrl,
                    apiKeys         = openAiApiKeys,
                    model           = openAiModel,
                    onBaseUrlChange = onOpenAiBaseUrlChange,
                    onApiKeysChange = onOpenAiApiKeysChange,
                    onModelChange   = onOpenAiModelChange,
                )
                SettingsPromptManager(
                    activeSystemPrompt             = activeSystemPrompt,
                    promptPresets                  = promptPresets,
                    promptUseEnglishLocale         = promptUseEnglishLocale,
                    onActiveSystemPromptChange     = onActiveSystemPromptChange,
                    onPromptUseEnglishLocaleChange = onPromptUseEnglishLocaleChange,
                    onSavePreset                   = onSavePreset,
                    onDeletePreset                 = onDeletePreset,
                )
            }
        }
    }
}

@Composable
private fun ProviderRadioOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick  = null,
            colors   = RadioButtonDefaults.colors(
                selectedColor   = ColorAccent,
                unselectedColor = MaterialTheme.colorScheme.onSurface,
            )
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = label,       style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}