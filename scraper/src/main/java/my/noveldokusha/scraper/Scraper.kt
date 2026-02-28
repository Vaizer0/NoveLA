package my.noveldokusha.scraper

import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.databases.BakaUpdates
import my.noveldokusha.scraper.databases.NovelUpdates
import my.noveldokusha.scraper.sources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Scraper @Inject constructor(
    networkClient: NetworkClient,
    localSource: LocalSource,
    appPreferences: AppPreferences,
    private val luaSourceLoader: LuaSourceLoader
) {
    val databasesList = setOf(
        NovelUpdates(networkClient),
        BakaUpdates(networkClient)
    )

    // LocalSource сохраняется для EPUB, остальные источники загружаются динамически
    val localSourcesList = setOf(localSource)
    
    // State для динамических источников
    private val _luaSources = MutableStateFlow<Set<SourceInterface>>(emptySet())
    val luaSources: StateFlow<Set<SourceInterface>> = _luaSources
    
    // Инициализация загрузки Lua источников
    init {
        loadLuaSources()
    }
    
    /**
     * Перезагрузка Lua источников (вызывать после установки/удаления)
     */
    fun reloadLuaSources() {
        kotlinx.coroutines.GlobalScope.launch {
            loadLuaSources()
        }
    }
    
    /**
     * Загрузка Lua источников из репозитория
     */
    private fun loadLuaSources() {
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val result = luaSourceLoader.loadAllSources()
                result.onSuccess { sources ->
                    _luaSources.value = sources.toSet()
                    Timber.d("Loaded ${sources.size} Lua sources")
                }
                result.onFailure { error ->
                    Timber.e(error, "Failed to load Lua sources")
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception while loading Lua sources")
            }
        }
    }
    
    // Общий список источников (локальные + динамические)
    val sourcesList: Set<SourceInterface>
        get() = localSourcesList + _luaSources.value

    val sourcesCatalogsList = sourcesList.filterIsInstance<SourceInterface.Catalog>()
    val sourcesCatalogsLanguagesList = sourcesCatalogsList.mapNotNull { it.language }.toSet()

    private fun String.isCompatibleWithBaseUrl(baseUrl: String): Boolean {
        val normalizedUrl = if (this.endsWith("/")) this else "$this/"
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return normalizedUrl.startsWith(normalizedBaseUrl)
    }

    fun getCompatibleSource(url: String): SourceInterface? {
        return sourcesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }
    }

    fun getCompatibleSourceCatalog(url: String): SourceInterface.Catalog? {
        return sourcesList.filterIsInstance<SourceInterface.Catalog>()
            .find { url.isCompatibleWithBaseUrl(it.baseUrl) }
    }

    fun getCompatibleDatabase(url: String): DatabaseInterface? =
        databasesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }

    fun isUrlSupported(url: String): Boolean {
        return getCompatibleSource(url) != null
    }

    fun getSourceDisplayName(url: String): String {
        return getCompatibleSource(url)?.let { source ->
            // Extract domain from baseUrl for display name
            val domain = source.baseUrl.substringBefore("/").substringAfter("://").substringBefore("www.")
            domain.replace(".", "").replace("-", " ").capitalize()
        } ?: "Unknown Source"
    }

    fun getSourceIconUrl(url: String): String? {
        val source = getCompatibleSource(url)
        return if (source is SourceInterface.Catalog) {
            source.iconUrl.toString()
        } else {
            null
        }
    }

    fun getSourceId(url: String): String? {
        return getCompatibleSource(url)?.id
    }
}
