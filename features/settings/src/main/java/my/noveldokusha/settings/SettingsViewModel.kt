package my.noveldokusha.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.noveldokusha.coreui.BaseViewModel
import my.noveldokusha.coreui.mappers.toPreferenceTheme
import my.noveldokusha.coreui.mappers.toTheme
import my.noveldokusha.coreui.theme.Themes
import my.noveldokusha.core.appPreferences.AppLanguage
import my.noveldokusha.data.AppRemoteRepository
import my.noveldokusha.data.AppRepository
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.Toasty
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.utils.asMutableStateOf
import my.noveldokusha.text_translator.domain.TranslationManager
import java.io.File
import javax.inject.Inject

@HiltViewModel
internal class SettingsViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val appScope: AppCoroutineScope,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val context: Context,
    private val translationManager: TranslationManager,
    stateHandle: SavedStateHandle,
    private val appFileResolver: AppFileResolver,
    private val appRemoteRepository: AppRemoteRepository,
    private val toasty: Toasty,
) : BaseViewModel() {

    var onRestartApp: (() -> Unit)? = null

    var isCleaningDatabase = mutableStateOf(false)
    var isCleaningImages = mutableStateOf(false)

    private val themeId by appPreferences.THEME_ID.state(viewModelScope)
    private val cloudflareBypassEnabled by appPreferences.CLOUDFLARE_BYPASS_ENABLED.state(viewModelScope)

    val state = SettingsScreenState(
        databaseSize = stateHandle.asMutableStateOf("databaseSize") { "" },
        imageFolderSize = stateHandle.asMutableStateOf("imageFolderSize") { "" },
        isCleaningDatabase = isCleaningDatabase,
        isCleaningImages = isCleaningImages,
        followsSystemTheme = appPreferences.THEME_FOLLOW_SYSTEM.state(viewModelScope),
        currentTheme = derivedStateOf { themeId.toTheme },
        currentLanguage = appPreferences.APP_LANGUAGE.state(viewModelScope),
        isTranslationSettingsVisible = mutableStateOf(translationManager.available),
        translationModelsStates = translationManager.models,
        updateAppSetting = SettingsScreenState.UpdateApp(
            currentAppVersion = appRemoteRepository.getCurrentAppVersion().toString(),
            showNewVersionDialog = mutableStateOf(null),
            appUpdateCheckerEnabled = appPreferences.GLOBAL_APP_UPDATER_CHECKER_ENABLED.state(
                viewModelScope
            ),
            checkingForNewVersion = mutableStateOf(false)
        ),
        libraryAutoUpdate = SettingsScreenState.LibraryAutoUpdate(
            autoUpdateEnabled = appPreferences.GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_ENABLED.state(
                viewModelScope
            ),
            autoUpdateIntervalHours = appPreferences.GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_INTERVAL_HOURS.state(
                viewModelScope
            )
        ),
        massAddDelayMs = appPreferences.MASS_ADD_DELAY_MS.state(viewModelScope),
        geminiApiKey = appPreferences.TRANSLATION_GEMINI_API_KEY.state(viewModelScope),
        geminiModel = appPreferences.TRANSLATION_GEMINI_MODEL.state(viewModelScope),
        translationProvider = appPreferences.TRANSLATION_PROVIDER.state(viewModelScope),
        googlePaApiKeys = appPreferences.TRANSLATION_GOOGLE_PA_API_KEYS.state(viewModelScope),
        scraperUserAgent = appPreferences.SCRAPER_USER_AGENT.state(viewModelScope),
        cloudflareBypassEnabled = appPreferences.CLOUDFLARE_BYPASS_ENABLED.state(viewModelScope),
        cloudflareChallengeTimeoutSeconds = appPreferences.CLOUDFLARE_CHALLENGE_TIMEOUT_SECONDS.state(viewModelScope),
    )

    init {
        updateDatabaseSize()
        updateImagesFolderSize()
        viewModelScope.launch {
            appRepository.eventDataRestored.collect {
                updateDatabaseSize()
                updateImagesFolderSize()
            }
        }

        // Show notification when Cloudflare bypass setting changes
        viewModelScope.launch {
            var previousValue = cloudflareBypassEnabled
            appPreferences.CLOUDFLARE_BYPASS_ENABLED.flow().collect { newValue ->
                if (newValue != previousValue) {
                    toasty.show(R.string.cloudflare_bypass_restart_required)
                    previousValue = newValue
                }
            }
        }

    }

    fun downloadTranslationModel(lang: String) {
        translationManager.downloadModel(lang)
    }

    fun removeTranslationModel(lang: String) {
        translationManager.removeModel(lang)
    }

    fun cleanDatabase() = appScope.launch(Dispatchers.IO) {
        if (isCleaningDatabase.value) return@launch // Prevent multiple simultaneous calls

        try {
            isCleaningDatabase.value = true
            toasty.show(R.string.cleaning_database)

            appRepository.settings.clearNonLibraryData()
            appRepository.vacuum()
            // Ждем завершения обновления размера базы данных
            updateDatabaseSizeAndWait()
            kotlinx.coroutines.delay(500) // Give time for UI update

            toasty.show(R.string.database_cleaned_successfully)

        } catch (e: Exception) {
            toasty.show(R.string.database_clean_failed)
            e.printStackTrace()
        } finally {
            isCleaningDatabase.value = false
        }
    }

    private suspend fun updateDatabaseSizeAndWait() {
        val size = appRepository.getDatabaseSizeBytes()
        // Переключаемся на Main для обновления состояния UI
        withContext(Dispatchers.Main) {
            state.databaseSize.value = Formatter.formatFileSize(appPreferences.context, size)
        }
    }

    fun cleanImagesFolder() = appScope.launch(Dispatchers.IO) {
        if (isCleaningImages.value) return@launch // Prevent multiple simultaneous calls

        try {
            isCleaningImages.value = true
            toasty.show(R.string.cleaning_images_folder)

            val libraryBooks = appRepository.libraryBooks.getAllInLibrary()
            val libraryFolderNames = libraryBooks.asSequence()
                .map { appFileResolver.getLocalBookFolderName(it.url) }
                .toSet()

            val booksFolder = appRepository.settings.folderBooks

            // Сначала удаляем папки, которые не соответствуют книгам в библиотеке
            val foldersToDelete = booksFolder.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.exists() }
                ?.filter { it.name !in libraryFolderNames }
                ?.toList() ?: emptyList()

            var deletedCount = 0
            foldersToDelete.forEach { folder ->
                try {
                    folder.deleteRecursively()
                    deletedCount++
                } catch (e: Exception) {
                    // Log error but continue with other folders
                    e.printStackTrace()
                }
            }

            // Очищаем Glide disk cache
            Glide.get(context).clearDiskCache()
            // Очищаем Coil disk cache (кэш сетевых обложек)
            context.cacheDir.resolve("image_cache").deleteRecursively()
            withContext(Dispatchers.Main) {
                Glide.get(context).clearMemory()
                coil.Coil.imageLoader(context).memoryCache?.clear()
            }

            // Обновляем размер папки после удаления
            updateImagesFolderSizeAndWait()
            kotlinx.coroutines.delay(500) // Give time for UI update

            if (deletedCount > 0) {
                toasty.show(context.getString(R.string.images_folder_cleaned, deletedCount))
            } else {
                toasty.show(R.string.images_folder_already_clean)
            }

        } catch (e: Exception) {
            toasty.show(R.string.images_folder_clean_failed)
            e.printStackTrace()
        } finally {
            isCleaningImages.value = false
        }
    }

    fun onFollowSystemChange(follow: Boolean) {
        appPreferences.THEME_FOLLOW_SYSTEM.value = follow
    }

    fun onThemeChange(themes: Themes) {
        appPreferences.THEME_ID.value = themes.toPreferenceTheme
    }

    fun onLanguageChange(language: AppLanguage) {
        appPreferences.APP_LANGUAGE.value = language
        toasty.show("Language changed to ${language.displayName}")
        // Restart the app to apply language changes
        onRestartApp?.invoke()
    }

    fun onGooglePaApiKeysChange(keys: String) {
        appPreferences.TRANSLATION_GOOGLE_PA_API_KEYS.value = keys
        // Сбрасываем кеш чтобы новые ключи проверились при следующем переводе
        appPreferences.TRANSLATION_GOOGLE_PA_CACHED_KEY.value = ""
        appPreferences.TRANSLATION_GOOGLE_PA_KEY_LAST_CHECKED.value = 0L
    }

    fun onGeminiApiKeyChange(apiKey: String) {
        appPreferences.TRANSLATION_GEMINI_API_KEY.value = apiKey
    }

    fun onGeminiModelChange(model: String) {
        appPreferences.TRANSLATION_GEMINI_MODEL.value = model
    }

    fun onPreferOnlineTranslationChange(prefer: Boolean) {
        appPreferences.TRANSLATION_PREFER_ONLINE.value = prefer
    }
    fun onTranslationProviderChange(provider: String) {
        appPreferences.TRANSLATION_PROVIDER.value = provider
    }

    private fun updateDatabaseSize() = viewModelScope.launch {
        updateDatabaseSizeAndWait()
    }

    private suspend fun updateImagesFolderSizeAndWait() {
        val size = getFolderSizeBytes(appRepository.settings.folderBooks)
        // Переключаемся на Main для обновления состояния UI
        withContext(Dispatchers.Main) {
            state.imageFolderSize.value = Formatter.formatFileSize(appPreferences.context, size)
        }
    }

    private fun updateImagesFolderSize() = viewModelScope.launch {
        updateImagesFolderSizeAndWait()
    }

    fun onCheckForUpdatesManual() {
        viewModelScope.launch {
            state.updateAppSetting.checkingForNewVersion.value = true
            val current = appRemoteRepository.getCurrentAppVersion()
            appRemoteRepository.getLastAppVersion()
                .onSuccess { new ->
                    if (new.version > current) {
                        state.updateAppSetting.showNewVersionDialog.value = new
                    } else {
                        toasty.show(R.string.you_already_have_the_last_version)
                    }
                }.onError {
                    toasty.show(R.string.failed_to_check_last_app_version)
                }
            state.updateAppSetting.checkingForNewVersion.value = false
        }
    }

    fun onMassAddDelayChange(newDelayMs: Long) {
        appPreferences.MASS_ADD_DELAY_MS.value = newDelayMs
    }

    private suspend fun getFolderSizeBytes(file: File): Long = withContext(Dispatchers.IO) {
        fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            dir.walk().forEach { if (it.isFile) total += it.length() }
            return total
        }
        // Папка с локальными файлами книг (обложки epub и т.д.)
        val booksSize = dirSize(file)
        // Coil disk cache (кэш сетевых обложек)
        val coilCacheSize = dirSize(context.cacheDir.resolve("image_cache"))
        booksSize + coilCacheSize
    }
}