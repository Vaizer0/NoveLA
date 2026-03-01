package my.noveldokusha.scraper

import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс провайдера Lua источников.
 * Позволяет модулю scraper не зависеть от Android Context напрямую.
 * Реализация находится в модуле app/data где есть доступ к Context.
 */
interface LuaSourceProvider {
    /** Flow установленных и включённых Lua источников */
    val sourcesFlow: Flow<List<SourceInterface>>

    /** Очистить кэш скомпилированных скриптов */
    fun clearCache()
}