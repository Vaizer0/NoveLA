package my.noveldokusha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.noveldokusha.core.Extension
import my.noveldokusha.core.ExtensionManager
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.scraper.configs.HtmlSelectors
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionRepository @Inject constructor(
    private val database: AppDatabase
) : ExtensionManager {

    private val extensionDao = database.extensionDao()

    override suspend fun getAllExtensions(): List<Extension> {
        return extensionDao.getAll().map { it.toCoreExtension() }
    }

    override suspend fun getInstalledExtensions(): List<Extension> {
        return extensionDao.getAllInstalled().map { it.toCoreExtension() }
    }

    override suspend fun getEnabledExtensions(): List<Extension> {
        return extensionDao.getAllEnabled().map { it.toCoreExtension() }
    }

    override fun getInstalledExtensionsFlow(): Flow<List<Extension>> {
        return extensionDao.getAllInstalledFlow().map { list ->
            list.map { it.toCoreExtension() }
        }
    }

    override suspend fun installExtension(extension: Extension) {
        val dbExtension = extension.toDbExtension()
        extensionDao.insert(dbExtension.copy(installed = true))
    }

    override suspend fun installExtensionFromInfo(id: String, name: String, version: String, language: String, imageUrl: String?) {
        val dbExtension = my.noveldokusha.feature.local_database.tables.Extension(
            id = id,
            name = name,
            fileName = "extension_${id}.jar",
            imageURL = imageUrl ?: "",
            language = language,
            version = version,
            md5 = "",
            enabled = true,
            installed = true,
            chapterType = "HTML",
            settings = "{}"
        )
        extensionDao.insert(dbExtension)
    }

    override suspend fun uninstallExtension(extensionId: String) {
        extensionDao.updateInstalled(extensionId, false)
    }

    override suspend fun enableExtension(extensionId: String) {
        extensionDao.updateEnabled(extensionId, true)
    }

    override suspend fun disableExtension(extensionId: String) {
        extensionDao.updateEnabled(extensionId, false)
    }

    override suspend fun updateExtensionSettings(extensionId: String, settings: String) {
        extensionDao.updateSettings(extensionId, settings)
    }

    suspend fun loadExtensionConfig(extension: my.noveldokusha.feature.local_database.tables.Extension): HtmlSelectors? {
        // TODO: Implement loading from network
        // For now, return null as network loading is not implemented yet
        Timber.w("loadExtensionConfig not implemented yet for ${extension.name}")
        return null
    }

    override suspend fun isExtensionInstalled(extensionId: String): Boolean {
        return extensionDao.exists(extensionId)
    }

    private fun my.noveldokusha.feature.local_database.tables.Extension.toCoreExtension(): Extension {
        return Extension(
            id = this.id,
            name = this.name,
            version = this.version,
            enabled = this.enabled,
            installed = this.installed
        )
    }

    private fun Extension.toDbExtension(): my.noveldokusha.feature.local_database.tables.Extension {
        return my.noveldokusha.feature.local_database.tables.Extension(
            id = this.id,
            name = this.name,
            fileName = "extension_${this.id}.jar",
            imageURL = "",
            language = "en",
            version = this.version,
            md5 = "",
            enabled = this.enabled,
            installed = this.installed,
            chapterType = "HTML",
            settings = "{}"
        )
    }
}
