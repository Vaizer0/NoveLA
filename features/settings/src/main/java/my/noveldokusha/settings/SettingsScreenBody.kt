package my.noveldokusha.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.theme.AppTheme
import my.noveldokusha.coreui.theme.DarkMode
import my.noveldokusha.coreui.theme.InternalTheme
import my.noveldokusha.coreui.theme.PreviewThemes
import my.noveldokusha.core.appPreferences.AppLanguage
import my.noveldokusha.settings.sections.AppUpdates
import my.noveldokusha.settings.sections.LibraryAutoUpdate
import my.noveldokusha.settings.sections.SettingsBackup
import my.noveldokusha.settings.sections.SettingsData
import my.noveldokusha.settings.sections.SettingsGeminiTranslation
import my.noveldokusha.settings.sections.SettingsLanguage
import my.noveldokusha.settings.sections.SettingsNetwork
import my.noveldokusha.settings.sections.SettingsTheme
import my.noveldokusha.settings.sections.SettingsRegexCleanup

@Composable
internal fun SettingsScreenBody(
    state: SettingsScreenState,
    modifier: Modifier = Modifier,
    onAppThemeSelected: (AppTheme) -> Unit,
    onDarkModeSelected: (DarkMode) -> Unit,
    onCleanDatabase: () -> Unit,
    onCleanImageFolder: () -> Unit,
    onMassAddDelayChange: (Long) -> Unit,
    onBackupData: () -> Unit,
    onRestoreData: () -> Unit,
    onCheckForUpdatesManual: () -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onGeminiModelChange: (String) -> Unit,
    onTranslationProviderChange: (String) -> Unit,
    onGooglePaApiKeysChange: (String) -> Unit,
    onOpenAiBaseUrlChange: (String) -> Unit,
    onOpenAiApiKeysChange: (String) -> Unit,
    onOpenAiModelChange: (String) -> Unit,
    onActiveSystemPromptChange: (String) -> Unit,
    onPromptUseEnglishLocaleChange: (Boolean) -> Unit,
    onSavePreset: (name: String, prompt: String) -> Unit,
    onDeletePreset: (name: String) -> Unit,
    onLlmBatchSizeChange: (Int) -> Unit,
    onLlmMaxOutputTokensChange: (Int) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onNavigateToRegexCleanup: () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        SettingsLanguage(
            currentLanguage = state.currentLanguage.value,
            onLanguageChange = onLanguageChange
        )
        HorizontalDivider()
        SettingsTheme(
            currentAppTheme = state.currentAppTheme.value,
            currentDarkMode = state.currentDarkMode.value,
            onAppThemeChange = onAppThemeSelected,
            onDarkModeChange = onDarkModeSelected,
        )
        HorizontalDivider()
        SettingsData(
            databaseSize = state.databaseSize.value,
            imagesFolderSize = state.imageFolderSize.value,
            isCleaningDatabase = state.isCleaningDatabase.value,
            isCleaningImages = state.isCleaningImages.value,
            onCleanDatabase = onCleanDatabase,
            onCleanImageFolder = onCleanImageFolder
        )
        HorizontalDivider()
        SettingsNetwork(
            scraperUserAgent = state.scraperUserAgent,
            cloudflareBypassEnabled = state.cloudflareBypassEnabled,
            cloudflareChallengeTimeoutSeconds = state.cloudflareChallengeTimeoutSeconds,
            massAddDelayMs = state.massAddDelayMs,
            onMassAddDelayChange = onMassAddDelayChange
        )
        HorizontalDivider()
        SettingsBackup(
            onBackupData = onBackupData,
            onRestoreData = onRestoreData
        )
        SettingsGeminiTranslation(
                translationProvider            = state.translationProvider.value,
                geminiApiKey                   = state.geminiApiKey.value,
                geminiModel                    = state.geminiModel.value,
                googlePaApiKeys                = state.googlePaApiKeys.value,
                openAiBaseUrl                  = state.openAiBaseUrl.value,
                openAiApiKeys                  = state.openAiApiKeys.value,
                openAiModel                    = state.openAiModel.value,
                activeSystemPrompt             = state.activeSystemPrompt.value,
                promptPresets                  = state.promptPresets.value,
                promptUseEnglishLocale         = state.promptUseEnglishLocale.value,
                onTranslationProviderChange    = onTranslationProviderChange,
                onGeminiApiKeyChange           = onGeminiApiKeyChange,
                onGeminiModelChange            = onGeminiModelChange,
                onGooglePaApiKeysChange        = onGooglePaApiKeysChange,
                onOpenAiBaseUrlChange          = onOpenAiBaseUrlChange,
                onOpenAiApiKeysChange          = onOpenAiApiKeysChange,
                onOpenAiModelChange            = onOpenAiModelChange,
                onActiveSystemPromptChange     = onActiveSystemPromptChange,
                onPromptUseEnglishLocaleChange = onPromptUseEnglishLocaleChange,
                onSavePreset                   = onSavePreset,
                onDeletePreset                 = onDeletePreset,
                llmBatchSize                   = state.llmBatchSize.value,
                llmMaxOutputTokens             = state.llmMaxOutputTokens.value,
                onLlmBatchSizeChange           = onLlmBatchSizeChange,
                onLlmMaxOutputTokensChange     = onLlmMaxOutputTokensChange,
            )
        HorizontalDivider()
        SettingsRegexCleanup(
            onNavigateToRegexCleanup = onNavigateToRegexCleanup
        )
        HorizontalDivider()
        LibraryAutoUpdate(state = state.libraryAutoUpdate)
        HorizontalDivider()
        AppUpdates(
            state = state.updateAppSetting,
            onCheckForUpdatesManual = onCheckForUpdatesManual
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "(°.°)",
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(120.dp))
    }
}


@PreviewThemes
@Composable
private fun Preview() {
    val isDark = isSystemInDarkTheme()
    InternalTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreenBody(
                state = SettingsScreenState(
                    currentAppTheme = remember { mutableStateOf(AppTheme.DEFAULT) },
                    currentDarkMode = remember { mutableStateOf(DarkMode.SYSTEM) },
                    currentLanguage = remember { derivedStateOf { AppLanguage.ENGLISH } },
                    databaseSize = remember { mutableStateOf("1 MB") },
                    imageFolderSize = remember { mutableStateOf("10 MB") },
                    isCleaningDatabase = remember { mutableStateOf(false) },
                    isCleaningImages = remember { mutableStateOf(false) },
                    updateAppSetting = SettingsScreenState.UpdateApp(
                        currentAppVersion = "1.0.0",
                        appUpdateCheckerEnabled = remember { mutableStateOf(true) },
                        showNewVersionDialog = remember { mutableStateOf(null) },
                        checkingForNewVersion = remember { mutableStateOf(true) },
                    ),
                    libraryAutoUpdate = SettingsScreenState.LibraryAutoUpdate(
                        autoUpdateEnabled = remember { mutableStateOf(true) },
                        autoUpdateIntervalHours = remember { mutableIntStateOf(24) },
                    ),
                    massAddDelayMs = remember { derivedStateOf { 2000L } },
                    geminiApiKey = remember { derivedStateOf { "" } },
                    geminiModel = remember { derivedStateOf { "" } },
                    translationProvider = remember { mutableStateOf("GOOGLE_PA") },
                    googlePaApiKeys = remember { derivedStateOf { "" } },
                    scraperUserAgent = remember { mutableStateOf("") },
                    cloudflareBypassEnabled = remember { mutableStateOf(true) },
                    cloudflareChallengeTimeoutSeconds = remember { mutableStateOf(120) },
                    openAiBaseUrl = remember { derivedStateOf { "" } },
                    openAiApiKeys = remember { derivedStateOf { "" } },
                    openAiModel = remember { derivedStateOf { "gpt-4o-mini" } },
                    activeSystemPrompt = remember { derivedStateOf { "" } },
                    promptPresets = remember { derivedStateOf { emptyList<Pair<String, String>>() } },
                    promptUseEnglishLocale = remember { derivedStateOf { true } },
                    llmBatchSize = remember { derivedStateOf { 60 } },
                    llmMaxOutputTokens = remember { derivedStateOf { 0 } },
                ),
                onCleanDatabase = { },
                onCleanImageFolder = { },
                onMassAddDelayChange = { },
                onBackupData = { },
                onRestoreData = { },
                onCheckForUpdatesManual = { },
                onGeminiApiKeyChange = { },
                onGeminiModelChange = { },
                onTranslationProviderChange = { },
                onGooglePaApiKeysChange = { },
                onOpenAiBaseUrlChange = { },
                onOpenAiApiKeysChange = { },
                onOpenAiModelChange = { },
                onActiveSystemPromptChange = { },
                onPromptUseEnglishLocaleChange = { },
                onSavePreset = { _, _ -> },
                onDeletePreset = { },
                onLlmBatchSizeChange = { },
                onLlmMaxOutputTokensChange = { },
                    onLanguageChange = { },
                    onAppThemeSelected = { },
                    onDarkModeSelected = { },
                    onNavigateToRegexCleanup = { },
            )
        }
    }
}