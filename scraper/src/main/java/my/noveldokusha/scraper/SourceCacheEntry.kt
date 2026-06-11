package my.noveldokusha.scraper

/**
 * Плоская копия SourceInterface для кэширования на диск.
 * Сериализация через Gson (доступен в scraper-модуле).
 */
data class SourceCacheEntry(
    val id: String,
    val name: String?,
    val nameStrId: Int,
    val baseUrl: String,
)