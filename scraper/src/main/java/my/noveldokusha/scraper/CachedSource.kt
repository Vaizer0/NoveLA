package my.noveldokusha.scraper

/**
 * Простая реализация SourceInterface для использования из кэша.
 * Живёт в scraper-модуле, чтобы соблюдать sealed interface ограничения.
 */
class CachedSource(
    override val id: String,
    override val nameStrId: Int,
    override val name: String?,
    override val baseUrl: String,
    override val isLocalSource: Boolean = false,
) : SourceInterface