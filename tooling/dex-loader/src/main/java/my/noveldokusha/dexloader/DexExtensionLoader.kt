package my.noveldokusha

import android.content.Context
import dalvik.system.DexClassLoader
import my.noveldokusha.scraper.SourceInterface
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DexExtensionLoader @Inject constructor(
    private val context: Context
) {

    private val extensionsDir = File(context.filesDir, "extensions").apply {
        mkdirs()
    }

    private val loadedSources = mutableMapOf<String, SourceInterface>()

    /**
     * Load extension from JAR file
     */
    fun loadExtension(jarFile: File, className: String): SourceInterface? {
        return try {
            Timber.d("Loading extension from ${jarFile.path}, class: $className")

            // Create optimized dex directory
            val optimizedDexDir = File(extensionsDir, "dex").apply {
                mkdirs()
            }

            // Create DexClassLoader
            val classLoader = DexClassLoader(
                jarFile.absolutePath,
                optimizedDexDir.absolutePath,
                null, // library path
                context.classLoader // parent class loader
            )

            // Load the source class
            val sourceClass = classLoader.loadClass(className)

            // Create instance
            val source = sourceClass.getDeclaredConstructor().newInstance() as SourceInterface

            // Cache the loaded source
            loadedSources[className] = source

            Timber.d("Successfully loaded extension: $className")
            source

        } catch (e: Exception) {
            Timber.e(e, "Failed to load extension from ${jarFile.path}")
            null
        }
    }

    /**
     * Unload extension
     */
    fun unloadExtension(className: String) {
        loadedSources.remove(className)
        Timber.d("Unloaded extension: $className")
    }

    /**
     * Get all loaded sources
     */
    fun getLoadedSources(): Map<String, SourceInterface> = loadedSources.toMap()

    /**
     * Check if extension is loaded
     */
    fun isExtensionLoaded(className: String): Boolean = loadedSources.containsKey(className)

    /**
     * Get loaded source by class name
     */
    fun getLoadedSource(className: String): SourceInterface? = loadedSources[className]

    /**
     * Save JAR file to extensions directory
     */
    fun saveJarFile(fileName: String, jarData: ByteArray): File {
        val jarFile = File(extensionsDir, fileName)
        jarFile.writeBytes(jarData)
        Timber.d("Saved JAR file: ${jarFile.path}")
        return jarFile
    }

    /**
     * Delete JAR file
     */
    fun deleteJarFile(fileName: String): Boolean {
        val jarFile = File(extensionsDir, fileName)
        val deleted = jarFile.delete()
        if (deleted) {
            Timber.d("Deleted JAR file: ${jarFile.path}")
        }
        return deleted
    }

    /**
     * List all JAR files in extensions directory
     */
    fun listJarFiles(): List<File> {
        return extensionsDir.listFiles { file ->
            file.isFile && file.extension.lowercase() == "jar"
        }?.toList() ?: emptyList()
    }
}
