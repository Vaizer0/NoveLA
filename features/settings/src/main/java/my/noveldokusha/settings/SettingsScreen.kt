package my.noveldokusha.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import my.noveldokusha.tooling.backup_create.onBackupCreate
import my.noveldokusha.tooling.backup_restore.onBackupRestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRestartApp: (() -> Unit)? = null
) {
    val viewModel: SettingsViewModel = viewModel()
    viewModel.onRestartApp = onRestartApp

    var currentScreen by remember { mutableStateOf("main") }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Text(
                        text = stringResource(id = R.string.title_settings),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            )
        },
        content = { innerPadding ->
            when (currentScreen) {
                "main" -> SettingsScreenBody(
                    state = viewModel.state,
                    onAppThemeSelected = viewModel::onAppThemeChange,
                    onDarkModeSelected = viewModel::onDarkModeChange,
                    onCleanDatabase = viewModel::cleanDatabase,
                    onCleanImageFolder = viewModel::cleanImagesFolder,
                    onMassAddDelayChange = viewModel::onMassAddDelayChange,
                    onBackupData = onBackupCreate(),
                    onRestoreData = onBackupRestore(),
                    onCheckForUpdatesManual = viewModel::onCheckForUpdatesManual,
                    onGeminiApiKeyChange = viewModel::onGeminiApiKeyChange,
                    onGeminiModelChange = viewModel::onGeminiModelChange,
                    onTranslationProviderChange = viewModel::onTranslationProviderChange,
                    onGooglePaApiKeysChange = viewModel::onGooglePaApiKeysChange,
                    onOpenAiBaseUrlChange = viewModel::onOpenAiBaseUrlChange,
                    onOpenAiApiKeysChange = viewModel::onOpenAiApiKeysChange,
                    onOpenAiModelChange = viewModel::onOpenAiModelChange,
                    onActiveSystemPromptChange = viewModel::onActiveSystemPromptChange,
                    onPromptUseEnglishLocaleChange = viewModel::onPromptUseEnglishLocaleChange,
                    onSavePreset = viewModel::onSavePromptPreset,
                    onDeletePreset = viewModel::onDeletePromptPreset,
                    onLlmBatchSizeChange = viewModel::onLlmBatchSizeChange,
                    onLlmMaxOutputTokensChange = viewModel::onLlmMaxOutputTokensChange,
                    onLanguageChange = viewModel::onLanguageChange,
                    onNavigateToRegexCleanup = {
                        currentScreen = "regex-cleanup"
                    },
                    modifier = Modifier.padding(innerPadding),
                )
                "regex-cleanup" -> {
                    val regexCleanupViewModel: RegexCleanupSettingsViewModel = viewModel()
                    RegexCleanupSettingsScreen(
                        viewModel = regexCleanupViewModel,
                        onNavigateBack = {
                            currentScreen = "main"
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    )
}