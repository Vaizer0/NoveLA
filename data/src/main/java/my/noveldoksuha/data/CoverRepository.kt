package my.noveldokusha.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.noveldokusha.core.atomicWrite
import my.noveldokusha.core.isCoverValid
import my.noveldokusha.core.isHttpsUrl
import my.noveldokusha.core.isImage
import my.noveldokusha.network.NetworkClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoverRepository @Inject constructor(
    private val networkClient: NetworkClient,
) {
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun ensureCover(
        coverFile: File,
        remoteUrl: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (remoteUrl.isNullOrBlank() || !remoteUrl.isHttpsUrl) return@withContext false
        if (isCoverValid(coverFile)) return@withContext true

        val lock = fileLocks.getOrPut(coverFile.absolutePath) { Mutex() }
        lock.withLock {
            if (isCoverValid(coverFile)) return@withLock true

            val bytes = try {
                networkClient.get(remoteUrl).use { response ->
                    if (!response.isSuccessful) return@withLock false
                    response.body?.bytes()
                }
            } catch (_: Exception) {
                return@withLock false
            }
            if (bytes == null || bytes.isEmpty() || !isImage(bytes)) {
                return@withLock false
            }

            try {
                atomicWrite(coverFile, bytes)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
