package my.noveldokusha.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import my.noveldokusha.core.appPreferences.AppLanguage
import my.noveldokusha.core.appPreferences.AppLanguageProvider
import my.noveldokusha.coreui.theme.AppTheme
import my.noveldokusha.coreui.theme.DarkMode
import my.noveldokusha.settings.sections.AppUpdates
import my.noveldokusha.settings.sections.LibraryAutoUpdate
import my.noveldokusha.settings.sections.SettingsBackup
import my.noveldokusha.settings.sections.SettingsData
import my.noveldokusha.settings.sections.SettingsGeminiTranslation
import my.noveldokusha.settings.sections.SettingsLanguage
import my.noveldokusha.settings.sections.SettingsNetwork
import my.noveldokusha.settings.sections.SettingsRegexCleanup
import my.noveldokusha.settings.sections.SettingsTtsAudioDownload
import my.noveldokusha.settings.sections.SettingsTtsVideoDownload
import my.noveldokusha.settings.sections.SettingsTheme
import my.noveldokusha.core.appPreferences.TtsAudioSource
import androidx.compose.runtime.mutableStateOf

@Composable
internal fun SettingsScreenBody(
    state: SettingsScreenState,
    destination: SettingsDestination,
    onDestinationChange: (SettingsDestination) -> Unit,
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
    onAudioSourceChange: (TtsAudioSource) -> Unit,
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

    val context = LocalContext.current

    when (destination) {
        SettingsDestination.HOME -> SettingsHome(
            modifier = modifier,
            onNavigate = onDestinationChange,
            currentLanguage = state.currentLanguage.value,
            audioVoiceId = state.audioVoiceId.value,
            audioSource = state.audioSource.value,
            videoOutputSelected = remember { my.noveldokusha.text_to_speech.TtsVideoPreferences(context).outputDirectoryUri.isNotBlank() },
        )

        SettingsDestination.GENERAL -> SettingsPage(modifier) {
            val currentLanguageObj = remember(state.currentLanguage.value) {
                AppLanguageProvider.fromCode(state.currentLanguage.value) ?: AppLanguageProvider.supportedLanguages.first()
            }
            SettingsLanguage(currentLanguage = currentLanguageObj, onLanguageChange = onLanguageChange)
        }

        SettingsDestination.APPEARANCE -> SettingsPage(modifier) {
            SettingsTheme(
                currentAppTheme = state.currentAppTheme.value,
                currentDarkMode = state.currentDarkMode.value,
                onAppThemeChange = onAppThemeSelected,
                onDarkModeChange = onDarkModeSelected,
            )
        }

        SettingsDestination.TRANSLATION -> SettingsPage(modifier) {
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
                onNovelPromptsClick = {},
            )
        }

        SettingsDestination.TEXT_CLEANUP -> SettingsPage(modifier) {
            SettingsRegexCleanup(onNavigateToRegexCleanup = onNavigateToRegexCleanup)
        }

        SettingsDestination.AUDIO_DOWNLOADS -> SettingsPage(modifier) {
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
        }

        SettingsDestination.VIDEO_DOWNLOADS -> SettingsPage(modifier) {
            SettingsTtsVideoDownload()
        }

        SettingsDestination.NETWORK -> SettingsPage(modifier) {
            SettingsNetwork(
                context = context,
                scraperUserAgent = state.scraperUserAgent,
                cloudflareBypassEnabled = state.cloudflareBypassEnabled,
                cloudflareChallengeTimeoutSeconds = state.cloudflareChallengeTimeoutSeconds,
                massAddDelayMs = state.massAddDelayMs,
                onMassAddDelayChange = onMassAddDelayChange,
                downloadDelayMs = state.downloadDelayMs,
                onDownloadDelayChange = onDownloadDelayChange,
            )
        }

        SettingsDestination.BACKUP_DATA -> SettingsPage(modifier) {
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
            Spacer(Modifier.height(16.dp))
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
        }

        SettingsDestination.LIBRARY -> SettingsPage(modifier) {
            LibraryAutoUpdate(state = state.libraryAutoUpdate)
        }

        SettingsDestination.APP_UPDATES -> SettingsPage(modifier) {
            AppUpdates(state = state.updateAppSetting, onCheckForUpdatesManual = onCheckForUpdatesManual)
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsHome(
    modifier: Modifier,
    onNavigate: (SettingsDestination) -> Unit,
    currentLanguage: String,
    audioVoiceId: String,
    audioSource: TtsAudioSource,
    videoOutputSelected: Boolean,
) {
    val languageSummary = AppLanguageProvider.fromCode(currentLanguage)?.getDisplayName() ?: currentLanguage
    val audioSummary = when {
        audioVoiceId.isBlank() -> "Choose a voice, speed and source"
        else -> "${audioVoiceId.take(28)} · ${audioSource.name.lowercase().replace('_', ' ')}"
    }
    val destinations = listOf(
        SettingsDestination.GENERAL,
        SettingsDestination.APPEARANCE,
        SettingsDestination.TRANSLATION,
        SettingsDestination.TEXT_CLEANUP,
        SettingsDestination.AUDIO_DOWNLOADS,
        SettingsDestination.VIDEO_DOWNLOADS,
        SettingsDestination.NETWORK,
        SettingsDestination.BACKUP_DATA,
        SettingsDestination.LIBRARY,
        SettingsDestination.APP_UPDATES,
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                    Text("Organized by category so the settings stay easy to scan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(destinations, key = { it.name }) { destination ->
            val summary = when (destination) {
                SettingsDestination.GENERAL -> "Language: $languageSummary"
                SettingsDestination.APPEARANCE -> "Theme and dark mode"
                SettingsDestination.TRANSLATION -> "Providers, models, prompts and limits"
                SettingsDestination.TEXT_CLEANUP -> "Regex cleanup rules"
                SettingsDestination.AUDIO_DOWNLOADS -> audioSummary
                SettingsDestination.VIDEO_DOWNLOADS -> if (videoOutputSelected) "Appearance, slideshow and output folder" else "Appearance, slideshow and choose output folder"
                SettingsDestination.NETWORK -> "Scraper, Cloudflare and request delays"
                SettingsDestination.BACKUP_DATA -> "Backup, restore and cache cleanup"
                SettingsDestination.LIBRARY -> "Automatic library update behavior"
                SettingsDestination.APP_UPDATES -> "Version checks and updates"
                SettingsDestination.HOME -> ""
            }
            val icon = when (destination) {
                SettingsDestination.GENERAL -> Icons.Outlined.Language
                SettingsDestination.APPEARANCE -> Icons.Outlined.Palette
                SettingsDestination.TRANSLATION -> Icons.Outlined.Translate
                SettingsDestination.TEXT_CLEANUP -> Icons.Outlined.CleaningServices
                SettingsDestination.AUDIO_DOWNLOADS -> Icons.Outlined.LibraryMusic
                SettingsDestination.VIDEO_DOWNLOADS -> Icons.Outlined.Movie
                SettingsDestination.NETWORK -> Icons.Outlined.Public
                SettingsDestination.BACKUP_DATA -> Icons.Outlined.Backup
                SettingsDestination.LIBRARY -> Icons.Outlined.LibraryBooks
                SettingsDestination.APP_UPDATES -> Icons.Outlined.SystemUpdate
                SettingsDestination.HOME -> Icons.Outlined.Settings
            }
            SettingsCategoryCard(
                title = destination.title,
                summary = summary,
                icon = icon,
                onClick = { onNavigate(destination) },
            )
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun SettingsCategoryCard(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
