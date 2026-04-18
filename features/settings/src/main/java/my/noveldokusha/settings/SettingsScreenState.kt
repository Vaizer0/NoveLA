package my.noveldokusha.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import my.noveldokusha.core.domain.RemoteAppVersion
import my.noveldokusha.text_translator.domain.TranslationModelState
import my.noveldokusha.coreui.theme.Themes
import my.noveldokusha.core.appPreferences.AppLanguage

data class SettingsScreenState(
    val databaseSize: MutableState<String>,
    val imageFolderSize: MutableState<String>,
    val isCleaningDatabase: State<Boolean>,
    val isCleaningImages: State<Boolean>,
    val followsSystemTheme: State<Boolean>,
    val currentTheme: State<Themes>,
    val currentLanguage: State<AppLanguage>,
    val isTranslationSettingsVisible: State<Boolean>,
    val translationModelsStates: SnapshotStateList<TranslationModelState>,
    val updateAppSetting: UpdateApp,
    val libraryAutoUpdate: LibraryAutoUpdate,
    val massAddDelayMs: State<Long>,
    val geminiApiKey: State<String>,
    val geminiModel: State<String>,
    val translationProvider: State<String>,
    val googlePaApiKeys: State<String>,
    val scraperUserAgent: MutableState<String>,
    val cloudflareBypassEnabled: MutableState<Boolean>,
    val cloudflareChallengeTimeoutSeconds: MutableState<Int>,
    // OpenAI-compatible
    val openAiBaseUrl: State<String>,
    val openAiApiKeys: State<String>,
    val openAiModel: State<String>,
    // Unified prompt manager (Gemini + OpenAI)
    val activeSystemPrompt: State<String>,
    val promptPresets: State<List<Pair<String, String>>>,
    val promptUseEnglishLocale: State<Boolean>,
) {
    data class UpdateApp(
        val currentAppVersion: String,
        val appUpdateCheckerEnabled: MutableState<Boolean>,
        val showNewVersionDialog: MutableState<RemoteAppVersion?>,
        val checkingForNewVersion: MutableState<Boolean>,
    )

    data class LibraryAutoUpdate(
        val autoUpdateEnabled: MutableState<Boolean>,
        val autoUpdateIntervalHours: MutableState<Int>,
    )
}