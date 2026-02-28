package my.noveldokusha.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.Yaml
import my.noveldokusha.core.ExtensionManager
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.SortOrder
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.configs.RepositoryIndex
import my.noveldokusha.scraper.configs.LanguageIndex
import my.noveldokusha.scraper.configs.SourceMetadata
import my.noveldokusha.data.ScraperRepository
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ExtensionsManagerViewModel @Inject constructor(
    private val extensionManager: ExtensionManager,
    private val httpClient: NetworkClient,
    private val appPreferences: AppPreferences,
    private val scraperRepository: ScraperRepository
) : ViewModel() {

    private val yaml = Yaml()

    private val _state = MutableStateFlow(ExtensionsScreenState())
    val state: StateFlow<ExtensionsScreenState> = _state.asStateFlow()

    // Cache for extension lists
    private var cachedAvailableExtensions: List<ExtensionInfo>? = null
    private var lastFetchTime: Long = 0
    private val CACHE_DURATION_MS = 5 * 60 * 1000 // 5 minutes

    init {
        // Initialize selected languages from preferences
        _state.update { it.copy(selectedLanguages = appPreferences.EXTENSIONS_LANGUAGES_FILTER.value) }
        
        // Load repository URL from preferences
        _state.update { it.copy(repositoryUrl = appPreferences.EXTENSIONS_REPOSITORY_URL.value) }

        // Collect installed extensions reactively
        viewModelScope.launch {
            extensionManager.getInstalledExtensionsFlow().collectLatest { extensions ->
                _state.update { it.copy(extensions = extensions) }
                // Update available extensions status when installed extensions change
                updateAvailableExtensionsStatus()
            }
        }

        loadAllAvailableExtensions()
    }

    fun onEvent(event: ExtensionsScreenEvent) {
        when (event) {
            is ExtensionsScreenEvent.OnExtensionToggle -> toggleExtension(event.extensionId, event.enabled)
            is ExtensionsScreenEvent.OnExtensionUninstall -> uninstallExtension(event.extensionId)
            is ExtensionsScreenEvent.OnExtensionConfigure -> configureExtension(event.extensionId)
            ExtensionsScreenEvent.OnRefresh -> refreshAll()
            ExtensionsScreenEvent.OnShowRepositoryDialog -> showRepositoryDialog()
            ExtensionsScreenEvent.OnHideRepositoryDialog -> hideRepositoryDialog()
            is ExtensionsScreenEvent.OnUpdateRepositoryUrl -> updateRepositoryUrl(event.url)
            is ExtensionsScreenEvent.OnSortOrderChange -> changeSortOrder(event.sortOrder)

            // Filter and navigation events
            is ExtensionsScreenEvent.OnLanguageFilterToggle -> toggleLanguageFilter(event.languageCode)
            is ExtensionsScreenEvent.OnLanguageFilterClear -> clearLanguageFilter(event.languageCode)
            ExtensionsScreenEvent.OnBackPressed -> handleBackPressed()
            is ExtensionsScreenEvent.OnExtensionInstall -> installExtension(event.extensionId)
            is ExtensionsScreenEvent.OnExtensionUninstallById -> uninstallExtensionById(event.extensionId)
        }
    }

    private fun loadInstalledExtensions() {
        viewModelScope.launch {
            try {
                val extensions = extensionManager.getInstalledExtensions()
                _state.update { it.copy(extensions = extensions) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load installed extensions")
                _state.update { it.copy(error = "Failed to load installed extensions") }
            }
        }
    }

    private fun loadAllAvailableExtensions(forceRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()

        // Use cached data if available and not expired
        if (!forceRefresh && cachedAvailableExtensions != null &&
            currentTime - lastFetchTime < CACHE_DURATION_MS) {
            _state.update { state ->
                state.copy(
                    availableExtensions = cachedAvailableExtensions!!.map { ext ->
                        ext.copy(
                            isInstalled = isExtensionInstalled(ext.id),
                            isEnabled = isExtensionEnabled(ext.id)
                        )
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                // Load all extensions from all languages
                val allExtensions = mutableListOf<ExtensionInfo>()

                // Get languages index
                val repositoryUrl = _state.value.repositoryUrl
                val response = httpClient.get(repositoryUrl)
                val yamlText = response.body?.string() ?: throw Exception("Empty response")

                val repositoryIndex = yaml.loadAs(yamlText, Map::class.java) as Map<String, Any>
                val languages = repositoryIndex["languages"] as Map<String, Map<String, Any>>

                // Load extensions for each language
                languages.forEach { (languageCode, langInfo) ->
                    try {
                        val langUrl = langInfo["url"] as String
                        val langResponse = httpClient.get(langUrl)
                        val langYamlText = langResponse.body?.string()
                        if (langYamlText != null) {
                            val languageData = yaml.loadAs(langYamlText, Map::class.java) as Map<String, Any>
                            val sources = languageData["sources"] as List<Map<String, Any>>
                            val extensions = sources.map { source ->
                                val installedVersion = getInstalledExtensionVersion(source["id"] as String)
                                val isUpdateAvailable = isUpdateAvailable(source["version"] as String, installedVersion)

                                ExtensionInfo(
                                    id = source["id"] as String,
                                    name = source["name"] as String,
                                    description = source["description"] as String,
                                    author = "", // TODO: Добавить author в YAML
                                    version = source["version"] as String,
                                    codeUrl = source["url"] as String,
                                    iconUrl = source["icon"] as String,
                                    language = languageCode,
                                    isInstalled = installedVersion != null,
                                    isEnabled = isExtensionEnabled(source["id"] as String),
                                    isUpdateAvailable = isUpdateAvailable
                                )
                            }
                            allExtensions.addAll(extensions)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load extensions for language $languageCode")
                        // Continue with other languages
                    }
                }

                // Cache the results
                cachedAvailableExtensions = allExtensions
                lastFetchTime = currentTime

                // Collect available languages from extensions
                val languageMap = mutableMapOf<String, Int>()
                allExtensions.forEach { ext ->
                    languageMap[ext.language] = languageMap.getOrDefault(ext.language, 0) + 1
                }
                val availableLanguages = languageMap.map { (code, count) ->
                    ExtensionLanguage(code = code, name = getLanguageDisplayName(code), count = count)
                }.sortedBy { it.name }

                _state.update {
                    it.copy(
                        availableExtensions = allExtensions,
                        availableLanguages = availableLanguages,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load available extensions")
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load available extensions"
                    )
                }
            }
        }
    }

    private fun toggleLanguageFilter(languageCode: String) {
        _state.update { state ->
            val newSelectedLanguages = if (state.selectedLanguages.contains(languageCode)) {
                state.selectedLanguages - languageCode
            } else {
                state.selectedLanguages + languageCode
            }
            state.copy(selectedLanguages = newSelectedLanguages)
        }
        // Save to preferences
        appPreferences.EXTENSIONS_LANGUAGES_FILTER.value = _state.value.selectedLanguages
    }

    private fun clearLanguageFilter(languageCode: String?) {
        if (languageCode == null) {
            // Clear all
            _state.update { it.copy(selectedLanguages = emptySet()) }
        } else {
            // Clear specific language
            _state.update { state ->
                state.copy(selectedLanguages = state.selectedLanguages - languageCode)
            }
        }
        // Save to preferences
        appPreferences.EXTENSIONS_LANGUAGES_FILTER.value = _state.value.selectedLanguages
    }

    private fun refreshAll() {
        loadInstalledExtensions()
        loadAllAvailableExtensions(forceRefresh = true)
    }

    private fun toggleExtension(extensionId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (enabled) {
                    extensionManager.enableExtension(extensionId)
                } else {
                    extensionManager.disableExtension(extensionId)
                }
                // Refresh the installed extensions list
                loadInstalledExtensions()
                // Update installation status in available extensions
                updateAvailableExtensionsStatus()
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle extension $extensionId")
                _state.update { it.copy(error = "Failed to toggle extension") }
            }
        }
    }

    private fun uninstallExtension(extensionId: String) {
        viewModelScope.launch {
            try {
                extensionManager.uninstallExtension(extensionId)
                // Refresh both lists
                loadInstalledExtensions()
                updateAvailableExtensionsStatus()
                
                // Reload Lua sources to update catalog
                scraperRepository.scraper.reloadLuaSources()
            } catch (e: Exception) {
                Timber.e(e, "Failed to uninstall extension $extensionId")
                _state.update { it.copy(error = "Failed to uninstall extension") }
            }
        }
    }

    private fun configureExtension(extensionId: String) {
        // TODO: Navigate to configuration screen
        Timber.d("Configure extension $extensionId")
    }

    private fun handleBackPressed() {
        Timber.d("Back pressed on extensions screen")
    }

    private fun showRepositoryDialog() {
        _state.update { it.copy(showRepositoryDialog = true) }
    }

    private fun hideRepositoryDialog() {
        _state.update { it.copy(showRepositoryDialog = false) }
    }

    private fun updateRepositoryUrl(url: String) {
        viewModelScope.launch {
            try {
                // Save to preferences
                appPreferences.EXTENSIONS_REPOSITORY_URL.value = url
                _state.update { it.copy(repositoryUrl = url) }
                hideRepositoryDialog()
                refreshAll()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update repository URL")
                _state.update { it.copy(error = "Failed to update repository URL") }
            }
        }
    }

    private fun changeSortOrder(sortOrder: SortOrder) {
        _state.update { it.copy(sortOrder = sortOrder) }
    }

    private fun installExtension(extensionId: String) {
        viewModelScope.launch {
            try {
                // Set installing state
                setExtensionInstalling(extensionId, true)

                // Find extension info
                val extensionInfo = _state.value.availableExtensions.find { it.id == extensionId }
                if (extensionInfo != null) {
                    // Download and install extension
                    installExtensionFromUrl(extensionInfo)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to install extension $extensionId")
                _state.update { it.copy(error = "Failed to install extension") }
            } finally {
                // Reset installing state and refresh data
                setExtensionInstalling(extensionId, false)
                // Refresh both lists to ensure consistency
                loadInstalledExtensions()
                updateAvailableExtensionsStatus()
            }
        }
    }

    private fun uninstallExtensionById(extensionId: String) {
        viewModelScope.launch {
            try {
                // Use string ID directly
                extensionManager.uninstallExtension(extensionId)
                // Refresh both lists
                loadInstalledExtensions()
                updateAvailableExtensionsStatus()
                
                // Reload Lua sources to update catalog
                scraperRepository.scraper.reloadLuaSources()
            } catch (e: Exception) {
                Timber.e(e, "Failed to uninstall extension $extensionId")
                _state.update { it.copy(error = "Failed to uninstall extension") }
            }
        }
    }

    private suspend fun installExtensionFromUrl(extensionInfo: ExtensionInfo) {
        try {
            // Create extension entry in database
            extensionManager.installExtensionFromInfo(
                id = extensionInfo.id,
                name = extensionInfo.name,
                version = extensionInfo.version,
                language = extensionInfo.language,
                imageUrl = extensionInfo.iconUrl
            )

            Timber.d("Successfully installed extension: ${extensionInfo.name}")

            // Refresh both lists
            loadInstalledExtensions()
            updateAvailableExtensionsStatus()

            // Reload Lua sources to update catalog
            scraperRepository.scraper.reloadLuaSources()

        } catch (e: Exception) {
            Timber.e(e, "Failed to install extension from URL: ${extensionInfo.codeUrl}")
            throw e
        } finally {
            // Clear installing state
            _state.update { state ->
                state.copy(
                    availableExtensions = state.availableExtensions.map { ext ->
                        if (ext.id == extensionInfo.id) {
                            ext.copy(isInstalling = false)
                        } else {
                            ext
                        }
                    }
                )
            }
        }
    }

    private fun setExtensionInstalling(extensionId: String, isInstalling: Boolean) {
        _state.update { state ->
            val updatedExtensions = state.availableExtensions.map { ext ->
                if (ext.id == extensionId) {
                    ext.copy(isInstalling = isInstalling)
                } else {
                    ext
                }
            }
            state.copy(availableExtensions = updatedExtensions)
        }
    }

    private fun updateAvailableExtensionsStatus() {
        _state.update { state ->
            val updatedExtensions = state.availableExtensions.map { ext ->
                val installedVersion = getInstalledExtensionVersion(ext.id)
                val isUpdateAvailable = isUpdateAvailable(ext.version, installedVersion)
                ext.copy(
                    isInstalled = installedVersion != null,
                    isEnabled = isExtensionEnabled(ext.id),
                    isUpdateAvailable = isUpdateAvailable
                )
            }
            state.copy(availableExtensions = updatedExtensions)
        }
    }

    private fun getInstalledExtensionVersion(extensionId: String): String? {
        return _state.value.extensions.find { it.id == extensionId }?.version
    }

    private fun isUpdateAvailable(availableVersion: String, installedVersion: String?): Boolean {
        if (installedVersion == null) return false

        return try {
            // Simple version comparison - split by dots and compare numbers
            val availableParts = availableVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val installedParts = installedVersion.split(".").map { it.toIntOrNull() ?: 0 }

            // Compare version parts
            for (i in 0 until maxOf(availableParts.size, installedParts.size)) {
                val availablePart = availableParts.getOrNull(i) ?: 0
                val installedPart = installedParts.getOrNull(i) ?: 0

                when {
                    availablePart > installedPart -> return true
                    availablePart < installedPart -> return false
                }
            }

            // Versions are equal
            false
        } catch (e: Exception) {
            Timber.w(e, "Failed to compare versions: available=$availableVersion, installed=$installedVersion")
            false
        }
    }

    private fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            "en" -> "English"
            "ru" -> "Русский"
            "zh" -> "中文"
            "es" -> "Español"
            "fr" -> "Français"
            "de" -> "Deutsch"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> languageCode.uppercase() // Fallback to uppercase code
        }
    }
    
    private fun isExtensionInstalled(extensionId: String): Boolean {
        return _state.value.extensions.any { it.id == extensionId }
    }
    
    private fun isExtensionEnabled(extensionId: String): Boolean {
        return _state.value.extensions.find { it.id == extensionId }?.enabled ?: false
    }
}

// Data classes for parsing external-sources JSON
@kotlinx.serialization.Serializable
private data class ExtensionsLanguagesIndex(
    val version: String,
    val lastUpdated: String,
    val languages: Map<String, ExtensionsLanguageInfo>
)

@kotlinx.serialization.Serializable
private data class ExtensionsLanguageInfo(
    val url: String,
    val count: Int? = null // Optional count field
)

@kotlinx.serialization.Serializable
private data class ExtensionsLanguageIndex(
    val language: String,
    val sources: List<ExtensionsSourceInfo>
)

@kotlinx.serialization.Serializable
private data class ExtensionsSourceInfo(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val codeUrl: String? = null,  // Legacy support
    val jarUrl: String? = null,   // New JAR URL field
    val ico: String? = null,      // Legacy support
    val iconUrl: String? = null   // New icon URL field
)
