package my.noveldokusha.scraper

import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.databases.BakaUpdates
import my.noveldokusha.scraper.databases.NovelUpdates
import my.noveldokusha.scraper.sources.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Scraper @Inject constructor(
    networkClient: NetworkClient,
    localSource: LocalSource
) {
    val databasesList = setOf(
        NovelUpdates(networkClient),
        BakaUpdates(networkClient)
    )

    val builtInSourcesList = setOf(
        localSource,
        ReadNovelFull(networkClient),
        RoyalRoad(networkClient),
        NovelHall(networkClient),
        Shuba69(networkClient),
        NovelBin(networkClient),
        ScribbleHub(networkClient),
        FreeWebNovel(networkClient),
        NovelFull(networkClient),
        AllNovel(networkClient),
        Jaomix(networkClient),
        RanobeLib(networkClient),
        RanobeHub(networkClient),
        Ifreedom(networkClient),
        Bookhamster(networkClient),
        BacaLightnovel(networkClient),
        Novelku(networkClient),
        NoBadNovel(networkClient),
        Ttkan(networkClient),
        Twkan(networkClient),
        NovLove(networkClient),
        WuxiaWorld_site(networkClient),
        NovelFire(networkClient),
        NovelBuddy(networkClient),
        WtrLabEn(networkClient),
    )

    // For now, only return built-in sources
    // TODO: Add external sources support when DI issues are resolved
    val sourcesList = builtInSourcesList

    val sourcesCatalogsList = sourcesList.filterIsInstance<SourceInterface.Catalog>()
    val sourcesCatalogsLanguagesList = sourcesCatalogsList.mapNotNull { it.language }.toSet()

    private fun String.isCompatibleWithBaseUrl(baseUrl: String): Boolean {
        val normalizedUrl = if (this.endsWith("/")) this else "$this/"
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return normalizedUrl.startsWith(normalizedBaseUrl)
    }

    fun getCompatibleSource(url: String): SourceInterface? {
        return builtInSourcesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }
    }

    fun getCompatibleSourceCatalog(url: String): SourceInterface.Catalog? {
        return builtInSourcesList.filterIsInstance<SourceInterface.Catalog>()
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
