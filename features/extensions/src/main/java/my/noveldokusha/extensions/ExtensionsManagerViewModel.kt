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
import my.noveldokusha.core.ExtensionManager
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.SortOrder
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.LuaSourceLoader
import my.noveldokusha.data.ScraperRepository
import org.yaml.snakeyaml.Yaml
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ExtensionsManagerViewModel @Inject constructor(
    private val extensionManager: ExtensionManager,
    private val httpClient: NetworkClient,
    private val appPreferences: AppPreferences,
    private val scraperRepository: ScraperRepository,
    private val luaSourceLoader: LuaSourceLoader,          // ← для скачивания .lua
) : ViewModel() {

    private val yaml = Yaml()

    private val _state = MutableStateFlow(ExtensionsScreenState())
    val state: StateFlow<ExtensionsScreenState> = _state.asStateFlow()

    private var cachedAvailableExtensions: List<ExtensionInfo>? = null
    private var lastFetchTime: Long = 0
    private val CACHE_DURATION_MS = 5 * 60 * 1000L

    init {
        _state.update {
            it.copy(
                selectedLanguages = appPreferences.EXTENSIONS_LANGUAGES_FILTER.value,
                repositoryUrl = appPreferences.EXTENSIONS_REPOSITORY_URL.value
            )
        }

        // Реактивно синхронизируем список установленных расширений
        viewModelScope.launch {
            extensionManager.getInstalledExtensionsFlow().collectLatest { extensions ->
                _state.update { it.copy(extensions = extensions) }
                updateAvailableExtensionsStatus()
            }
        }

        loadAllAvailableExtensions()
    }

    fun onEvent(event: ExtensionsScreenEvent) = when (event) {
        is ExtensionsScreenEvent.OnExtensionToggle       -> toggleExtension(event.extensionId, event.enabled)
        is ExtensionsScreenEvent.OnExtensionUninstall    -> uninstallExtension(event.extensionId)
        is ExtensionsScreenEvent.OnExtensionConfigure    -> Unit // TODO
        ExtensionsScreenEvent.OnRefresh                  -> refreshAll()
        ExtensionsScreenEvent.OnShowRepositoryDialog     -> _state.update { it.copy(showRepositoryDialog = true) }
        ExtensionsScreenEvent.OnHideRepositoryDialog     -> _state.update { it.copy(showRepositoryDialog = false) }
        is ExtensionsScreenEvent.OnUpdateRepositoryUrl   -> updateRepositoryUrl(event.url)
        is ExtensionsScreenEvent.OnSortOrderChange       -> _state.update { it.copy(sortOrder = event.sortOrder) }
        is ExtensionsScreenEvent.OnLanguageFilterToggle  -> toggleLanguageFilter(event.languageCode)
        is ExtensionsScreenEvent.OnLanguageFilterClear   -> clearLanguageFilter(event.languageCode)
        ExtensionsScreenEvent.OnBackPressed              -> Unit
        is ExtensionsScreenEvent.OnExtensionInstall      -> installExtension(event.extensionId)
        is ExtensionsScreenEvent.OnExtensionUninstallById -> uninstallExtensionById(event.extensionId)
    }

    // ── Загрузка доступных расширений из репозитория ─────────────────────────

    private fun loadAllAvailableExtensions(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedAvailableExtensions != null && now - lastFetchTime < CACHE_DURATION_MS) {
            applyCache()
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val repoUrl  = _state.value.repositoryUrl
                val response = httpClient.get(repoUrl)
                val yaml     = Yaml()
                @Suppress("UNCHECKED_CAST")
                val repoIndex = yaml.loadAs(
                    response.body?.string() ?: error("Empty response"),
                    Map::class.java
                ) as Map<String, Any>

                @Suppress("UNCHECKED_CAST")
                val languages = repoIndex["languages"] as Map<String, Map<String, Any>>
                val allExt = mutableListOf<ExtensionInfo>()

                languages.forEach { (langCode, langInfo) ->
                    try {
                        val langUrl = langInfo["url"] as String
                        val langResp = httpClient.get(langUrl)
                        @Suppress("UNCHECKED_CAST")
                        val langData = yaml.loadAs(
                            langResp.body?.string() ?: return@forEach,
                            Map::class.java
                        ) as Map<String, Any>

                        @Suppress("UNCHECKED_CAST")
                        val sources = langData["sources"] as List<Map<String, Any>>
                        sources.forEach { src ->
                            val id = src["id"] as String
                            val installedVer = getInstalledVersion(id)
                            allExt.add(
                                ExtensionInfo(
                                    id               = id,
                                    name             = src["name"] as String,
                                    description      = src["description"] as? String ?: "",
                                    author           = src["author"] as? String ?: "",
                                    version          = src["version"] as String,
                                    codeUrl          = src["url"] as String,
                                    iconUrl          = src["icon"] as? String ?: "",
                                    language         = langCode,
                                    isInstalled      = installedVer != null,
                                    isEnabled        = isEnabled(id),
                                    isUpdateAvailable = isUpdateAvailable(src["version"] as String, installedVer)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load language $langCode")
                    }
                }

                cachedAvailableExtensions = allExt
                lastFetchTime = now

                val langList = allExt.groupBy { it.language }
                    .map { (code, list) ->
                        ExtensionLanguage(code, getLanguageDisplayName(code), list.size)
                    }
                    .sortedBy { it.name }

                _state.update {
                    it.copy(
                        availableExtensions = allExt,
                        availableLanguages  = langList,
                        isLoading           = false,
                        error               = null
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load extensions repository")
                _state.update { it.copy(isLoading = false, error = "Failed to load extensions: ${e.message}") }
            }
        }
    }

    private fun applyCache() {
        _state.update { state ->
            state.copy(
                availableExtensions = cachedAvailableExtensions!!.map { ext ->
                    ext.copy(
                        isInstalled = getInstalledVersion(ext.id) != null,
                        isEnabled   = isEnabled(ext.id)
                    )
                }
            )
        }
    }

    // ── Установка ─────────────────────────────────────────────────────────────

    /**
     * Процесс установки:
     * 1. Скачать .lua файл на диск через LuaSourceLoader
     * 2. Сохранить запись в БД через ExtensionManager
     *    (codeUrl сохраняется в settings как JSON для последующей загрузки)
     * 3. Scraper перезагрузится реактивно через Flow установленных расширений
     */
    private fun installExtension(extensionId: String) {
        viewModelScope.launch {
            val extInfo = _state.value.availableExtensions.find { it.id == extensionId } ?: return@launch
            setInstalling(extensionId, true)
            try {
                // Шаг 1: Скачать .lua на диск
                val downloaded = luaSourceLoader.downloadAndCacheScript(extensionId, extInfo.codeUrl)
                if (!downloaded) {
                    _state.update { it.copy(error = "Failed to download script for ${extInfo.name}") }
                    return@launch
                }

                // Шаг 2: Записать в БД
                extensionManager.installExtensionFromInfo(
                    id       = extensionId,
                    name     = extInfo.name,
                    version  = extInfo.version,
                    language = extInfo.language,
                    imageUrl = extInfo.iconUrl,
                    codeUrl  = extInfo.codeUrl
                )
                // Шаг 2б: Сохранить codeUrl в settings как YAML,
                // чтобы LuaSourceLoader знал откуда перескачать при следующем запуске
                val settingsYaml = "codeUrl: \"${extInfo.codeUrl}\""
                extensionManager.updateExtensionSettings(extensionId, settingsYaml)

                Timber.d("Installed extension: ${extInfo.name}")
                // Шаг 3: Scraper обновится реактивно через extensionRepository.getInstalledExtensionsFlow()
            } catch (e: Exception) {
                Timber.e(e, "Failed to install ${extInfo.name}")
                _state.update { it.copy(error = "Failed to install ${extInfo.name}: ${e.message}") }
                // Если установка не удалась, удаляем скачанный файл
                luaSourceLoader.removeScript(extensionId)
            } finally {
                setInstalling(extensionId, false)
            }
        }
    }

    // ── Удаление ──────────────────────────────────────────────────────────────

    private fun uninstallExtension(extensionId: String) = uninstallExtensionById(extensionId)

    private fun uninstallExtensionById(extensionId: String) {
        viewModelScope.launch {
            try {
                extensionManager.uninstallExtension(extensionId)
                luaSourceLoader.removeScript(extensionId)
                // Scraper обновится реактивно
            } catch (e: Exception) {
                Timber.e(e, "Failed to uninstall $extensionId")
                _state.update { it.copy(error = "Failed to uninstall extension") }
            }
        }
    }

    // ── Вкл/выкл ─────────────────────────────────────────────────────────────

    private fun toggleExtension(extensionId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (enabled) extensionManager.enableExtension(extensionId)
                else         extensionManager.disableExtension(extensionId)
                // Scraper обновится реактивно
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle extension $extensionId")
                _state.update { it.copy(error = "Failed to toggle extension") }
            }
        }
    }

    // ── Фильтры / репозиторий ────────────────────────────────────────────────

    private fun toggleLanguageFilter(code: String) {
        _state.update { state ->
            val updated = if (code in state.selectedLanguages) state.selectedLanguages - code
            else state.selectedLanguages + code
            state.copy(selectedLanguages = updated)
        }
        appPreferences.EXTENSIONS_LANGUAGES_FILTER.value = _state.value.selectedLanguages
    }

    private fun clearLanguageFilter(code: String?) {
        _state.update { it.copy(selectedLanguages = if (code == null) emptySet() else it.selectedLanguages - code) }
        appPreferences.EXTENSIONS_LANGUAGES_FILTER.value = _state.value.selectedLanguages
    }

    private fun refreshAll() = loadAllAvailableExtensions(forceRefresh = true)

    private fun updateRepositoryUrl(url: String) {
        viewModelScope.launch {
            appPreferences.EXTENSIONS_REPOSITORY_URL.value = url
            _state.update { it.copy(repositoryUrl = url, showRepositoryDialog = false) }
            refreshAll()
        }
    }

    // ── Вспомогательные ──────────────────────────────────────────────────────

    private fun updateAvailableExtensionsStatus() {
        _state.update { state ->
            state.copy(
                availableExtensions = state.availableExtensions.map { ext ->
                    val installedVer = getInstalledVersion(ext.id)
                    ext.copy(
                        isInstalled       = installedVer != null,
                        isEnabled         = isEnabled(ext.id),
                        isUpdateAvailable = isUpdateAvailable(ext.version, installedVer)
                    )
                }
            )
        }
    }

    private fun setInstalling(id: String, installing: Boolean) {
        _state.update { state ->
            state.copy(availableExtensions = state.availableExtensions.map {
                if (it.id == id) it.copy(isInstalling = installing) else it
            })
        }
    }

    private fun getInstalledVersion(id: String) =
        _state.value.extensions.find { it.id == id }?.version

    private fun isEnabled(id: String) =
        _state.value.extensions.find { it.id == id }?.enabled ?: false

    private fun isUpdateAvailable(available: String, installed: String?): Boolean {
        if (installed == null) return false
        return try {
            val a = available.split(".").map { it.toIntOrNull() ?: 0 }
            val b = installed.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val av = a.getOrElse(i) { 0 }
                val bv = b.getOrElse(i) { 0 }
                if (av > bv) return true
                if (av < bv) return false
            }
            false
        } catch (e: Exception) { false }
    }

    private fun getLanguageDisplayName(code: String): String {
        if (code == "multi") return "Multilanguage"
        return try {
            val locale = java.util.Locale.forLanguageTag(code)
            locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercaseChar() }
                .takeIf { it.isNotBlank() && it != code } ?: code.uppercase()
        } catch (e: Exception) {
            code.uppercase()
        }
    }
}