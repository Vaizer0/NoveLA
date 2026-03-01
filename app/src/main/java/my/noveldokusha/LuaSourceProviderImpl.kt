package my.noveldokusha.scraper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.noveldokusha.core.ExtensionRepositoryInterface
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация LuaSourceProvider.
 * Живёт в модуле app/data — там есть доступ к Context через LuaSourceLoader.
 * Подписывается на изменения установленных расширений и перезагружает источники.
 */
@Singleton
class LuaSourceProviderImpl @Inject constructor(
    private val luaSourceLoader: LuaSourceLoader,
    private val extensionRepository: ExtensionRepositoryInterface
) : LuaSourceProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sourcesFlow = MutableStateFlow<List<SourceInterface>>(emptyList())
    override val sourcesFlow: Flow<List<SourceInterface>> = _sourcesFlow.asStateFlow()

    init {
        scope.launch {
            extensionRepository.getInstalledExtensionsFlow().collect {
                reload()
            }
        }
    }

    override fun clearCache() = luaSourceLoader.clearCache()

    private suspend fun reload() {
        try {
            luaSourceLoader.loadAllSources()
                .onSuccess { sources ->
                    _sourcesFlow.value = sources
                    Timber.d("LuaSourceProvider: loaded ${sources.size} sources")
                }
                .onFailure { err ->
                    Timber.e(err, "LuaSourceProvider: reload failed")
                }
        } catch (e: Exception) {
            Timber.e(e, "LuaSourceProvider: exception during reload")
        }
    }
}