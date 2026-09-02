package my.noveldokusha.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons

import my.noveldokusha.coreui.theme.AppTheme
import my.noveldokusha.coreui.theme.DarkMode
import my.noveldokusha.coreui.theme.InternalTheme
import my.noveldokusha.coreui.theme.PreviewThemes
import my.noveldokusha.core.appPreferences.AppLanguage
import my.noveldokusha.core.appPreferences.AppLanguageProvider
import my.noveldokusha.core.appPreferences.NovelPromptData
import my.noveldokusha.settings.sections.AppUpdates
import my.noveldokusha.settings.sections.LibraryAutoUpdate
import my.noveldokusha.settings.sections.SettingsBackup
import my.noveldokusha.settings.sections.SettingsData
import my.noveldokusha.settings.sections.SettingsGeminiTranslation
import my.noveldokusha.settings.sections.SettingsLanguage
import my.noveldokusha.settings.sections.SettingsNovelPromptsDialog
import my.noveldokusha.settings.sections.SettingsNetwork
import my.noveldokusha.settings.sections.SettingsTheme
import my.noveldokusha.settings.sections.SettingsRegexCleanup
import my.noveldokusha.settings.sections.SettingsTtsAudioDownload
import my.noveldokusha.settings.sections.SettingsTtsVideoDownload

@Composable
internal fun SettingsScreenBody(
    state: SettingsScreenState,
    modifier: Modifier = Modifier,
    onRefreshSizes: () -> Unit,
    onAppThemeSelected: (AppTheme) -> Unit,
    onDarkModeSelected: (DarkMode) -> Unit,
    onRequestCleanDatabase: () -> Unit,
    onRequestCleanImageFolder: () -> Unit,
    onRequestCleanChapterCache: () -> Unit,
    onConfirmClean: () -> Unit,
    onDismissClean: () -> Unit,
    onMassAddDelayChange: (Long) -> Unit,
    onDownloadDelayChange: (Long) -> Unit,
    onBackupData: () -> Unit,
    onRestoreData: () -> Unit,
    onCheckForUpdatesManual: () -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onGeminiModelChange: (String) -> Unit,
    onTranslationProviderChange: (String) -> Unit,
    onTranslationGlobalModeChange: (Boolean) -> Unit,
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
    onDeleteNovelPrompt: (String) -> Unit,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    onAutoBackupSelectDirectory: () -> Unit,
    onAutoBackupMaxCountChange: (Int) -> Unit,
    onAutoBackupIntervalMinutesChange: (Long) -> Unit,
    onAutoBackupIncludeImagesChange: (Boolean) -> Unit,
    onAutoBackupIncludeSettingsChange: (Boolean) -> Unit,
    onAutoBackupIncludePluginsChange: (Boolean) -> Unit,
    onAudioVoiceChange: (enginePackage: String, voiceId: String) -> Unit,
    onAudioVoiceSpeedChange: (Float) -> Unit,
    onAudioVoicePitchChange: (Float) -> Unit,
    onAudioSourceChange: (my.noveldokusha.core.appPreferences.TtsAudioSource) -> Unit,
    onAudioSelectDirectory: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshSizes()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showNovelPromptsDialog = remember { mutableStateOf(false) }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        val currentLanguageObj = remember(state.currentLanguage.value) {
            AppLanguageProvider.fromCode(state.currentLanguage.value) ?: AppLanguageProvider.supportedLanguages.first()
        }
        SettingsLanguage(currentLanguage = currentLanguageObj, onLanguageChange = onLanguageChange)
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
            chapterCacheSize = state.chapterCacheSize.value,
            isCleaningDatabase = state.isCleaningDatabase.value,
            isCleaningImages = state.isCleaningImages.value,
            isCleaningChapterCache = state.isCleaningChapterCache.value,
            onRequestCleanDatabase = onRequestCleanDatabase,
            onRequestCleanImageFolder = onRequestCleanImageFolder,
            onRequestCleanChapterCache = onRequestCleanChapterCache,
        )
        HorizontalDivider()
        val context = LocalContext.current
        SettingsNetwork(
            context = context,
            scraperUserAgent = state.scraperUserAgent,
            cloudflareBypassEnabled = state.cloudflareBypassEnabled,
            cloudflareChallengeTimeoutSeconds = state.cloudflareChallengeTimeoutSeconds,
            massAddDelayMs = state.massAddDelayMs,
            onMassAddDelayChange = onMassAddDelayChange,
            downloadDelayMs = state.downloadDelayMs,
            onDownloadDelayChange = onDownloadDelayChange
        )
        HorizontalDivider()
        SettingsBackup(
            onBackupData = onBackupData,
            onRestoreData = onRestoreData,
            autoBackupEnabled = state.autoBackupEnabled.value,
            onAutoBackupEnabledChange = onAutoBackupEnabledChange,
            autoBackupDirectoryUri = state.autoBackupDirectoryUri.value,
            autoBackupDirectoryDisplayName = state.autoBackupDirectoryDisplayName.value,
            onAutoBackupSelectDirectory = onAutoBackupSelectDirectory,
            autoBackupMaxCount = state.autoBackupMaxCount.value,
            onAutoBackupMaxCountChange = onAutoBackupMaxCountChange,
            autoBackupIntervalMinutes = state.autoBackupIntervalMinutes.value,
            onAutoBackupIntervalMinutesChange = onAutoBackupIntervalMinutesChange,
            autoBackupIncludeImages = state.autoBackupIncludeImages.value,
            onAutoBackupIncludeImagesChange = onAutoBackupIncludeImagesChange,
            autoBackupIncludeSettings = state.autoBackupIncludeSettings.value,
            onAutoBackupIncludeSettingsChange = onAutoBackupIncludeSettingsChange,
            autoBackupIncludePlugins = state.autoBackupIncludePlugins.value,
            onAutoBackupIncludePluginsChange = onAutoBackupIncludePluginsChange,
            autoBackupLastTimestamp = state.autoBackupLastTimestamp.value,
        )
        SettingsGeminiTranslation(
            translationProvider = state.translationProvider.value,
            onTranslationProviderChange = onTranslationProviderChange,
            translationGlobalMode = state.translationGlobalMode.value,
            onTranslationGlobalModeChange = onTranslationGlobalModeChange,
            geminiApiKey = state.geminiApiKey.value,
            geminiModel = state.geminiModel.value,
            googlePaApiKeys = state.googlePaApiKeys.value,
            openAiBaseUrl = state.openAiBaseUrl.value,
            openAiApiKeys = state.openAiApiKeys.value,
            openAiModel = state.openAiModel.value,
            activeSystemPrompt = state.activeSystemPrompt.value,
            promptPresets = state.promptPresets.value,
            promptUseEnglishLocale = state.promptUseEnglishLocale.value,
            onGeminiApiKeyChange = onGeminiApiKeyChange,
            onGeminiModelChange = onGeminiModelChange,
            onGooglePaApiKeysChange = onGooglePaApiKeysChange,
            onOpenAiBaseUrlChange = onOpenAiBaseUrlChange,
            onOpenAiApiKeysChange = onOpenAiApiKeysChange,
            onOpenAiModelChange = onOpenAiModelChange,
            onActiveSystemPromptChange = onActiveSystemPromptChange,
            onPromptUseEnglishLocaleChange = onPromptUseEnglishLocaleChange,
            onSavePreset = onSavePreset,
            onDeletePreset = onDeletePreset,
            llmBatchSize = state.llmBatchSize.value,
            llmMaxOutputTokens = state.llmMaxOutputTokens.value,
            onLlmBatchSizeChange = onLlmBatchSizeChange,
            onLlmMaxOutputTokensChange = onLlmMaxOutputTokensChange,
            novelPromptCount = state.translationNovelPrompts.value.size,
            onNovelPromptsClick = { showNovelPromptsDialog.value = true },
        )
        SettingsRegexCleanup(onNavigateToRegexCleanup = onNavigateToRegexCleanup)
        HorizontalDivider()
        SettingsTtsAudioDownload(
            voiceId = state.audioVoiceId.value,
            voiceEngine = state.audioVoiceEngine.value,
            speed = state.audioVoiceSpeed.value,
            pitch = state.audioVoicePitch.value,
            source = state.audioSource.value,
            speedRange = 0.1f..5f,
            pitchRange = 0.1f..5f,
            onVoiceChange = onAudioVoiceChange,
            onSpeedChange = onAudioVoiceSpeedChange,
            onPitchChange = onAudioVoicePitchChange,
            onSourceChange = onAudioSourceChange,
            onSelectDirectory = onAudioSelectDirectory,
            directoryDisplayName = state.audioDirectoryDisplayName.value,
        )
        HorizontalDivider()
        SettingsTtsVideoDownload()
        HorizontalDivider()
        LibraryAutoUpdate(state = state.libraryAutoUpdate)
        HorizontalDivider()
        AppUpdates(state = state.updateAppSetting, onCheckForUpdatesManual = onCheckForUpdatesManual)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "(°.°)",
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(120.dp))
    }
}
