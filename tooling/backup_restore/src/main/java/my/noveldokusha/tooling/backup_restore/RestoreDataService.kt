package my.noveldokusha.tooling.backup_restore

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.coreui.states.removeProgressBar
import my.noveldokusha.coreui.states.text
import my.noveldokusha.coreui.states.title
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.BookChaptersRepository
import my.noveldokusha.data.ChapterBodyRepository
import my.noveldokusha.data.DownloaderRepository
import my.noveldokusha.data.LibraryBooksRepository
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.tryAsResponse
import my.noveldokusha.core.utils.Extra_Uri
import my.noveldokusha.core.utils.isServiceRunning
import my.noveldokusha.feature.local_database.AppDatabase
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject

@AndroidEntryPoint
class RestoreDataService : Service() {
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var appRepository: AppRepository

    @Inject
    lateinit var appFileResolver: AppFileResolver

    @Inject
    lateinit var notificationsCenter: NotificationsCenter

    @Inject
    lateinit var appCoroutineScope: AppCoroutineScope

    @Inject
    lateinit var downloaderRepository: DownloaderRepository

    private class IntentData : Intent {
        var uri by Extra_Uri()

        constructor(intent: Intent) : super(intent)
        constructor(ctx: Context, uri: Uri) : super(ctx, RestoreDataService::class.java) {
            this.uri = uri
        }
    }

    companion object {
        fun start(ctx: Context, uri: Uri) {
            if (!isRunning(ctx))
                ContextCompat.startForegroundService(ctx, IntentData(ctx, uri))
        }

        private fun isRunning(context: Context): Boolean =
            context.isServiceRunning(RestoreDataService::class.java)
    }

    private val channelName by lazy { getString(R.string.notification_channel_name_restore_backup) }
    private val channelId = "Restore backup"
    private val notificationId = channelId.hashCode()

    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationBuilder = notificationsCenter.showNotification(
            notificationId = notificationId,
            channelId = channelId,
            channelName = channelName
        )
        startForeground(notificationId, notificationBuilder.build())
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Timber.e("RestoreDataService: intent is null")
            return START_NOT_STICKY
        }
        val intentData = IntentData(intent)

        if (job?.isActive == true) {
            Timber.w("RestoreDataService: job already active")
            return START_NOT_STICKY
        }
        job = CoroutineScope(Dispatchers.IO).launch {
            tryAsResponse {
                Timber.d("RestoreDataService: Starting restore from URI: ${intentData.uri}")
                restoreData(intentData.uri)
                appRepository.eventDataRestored.emit(Unit)
                Timber.d("RestoreDataService: Restore completed successfully")
            }.onError {
                Timber.e(it.exception, "RestoreDataService: Restore failed with error")
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore failure".hashCode()
                ) {
                    removeProgressBar()
                    title = getString(R.string.failed_to_restore_cant_access_file)
                    text = "Error: ${it.exception.message?.take(100) ?: "Unknown error"}"
                }
            }
            stopSelf(startId)
        }
        return START_STICKY
    }

    /**
     * Restore data function. Restores the library and images data given an uri.
     * The uri must point to a zip file where there must be a root file
     * "database.sqlite3" and an optional "books" folder where all the images
     * are stored (each subfolder is a book with its own structure).
     *
     * IMPORTANT: Only books with inLibrary=true are restored. Books that were
     * previously deleted (inLibrary=false) are ignored even if they exist in the backup.
     */
    private suspend fun restoreData(uri: Uri) = withContext(Dispatchers.IO) {

        Timber.d("restoreData: Starting restore process")
        notificationsCenter.modifyNotification(
            notificationBuilder,
            notificationId = notificationId
        ) {
            title = getString(R.string.restore_data)
            text = getString(R.string.loading_data)
            setProgress(100, 0, true)
        }

        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Timber.e("restoreData: Failed to open input stream from URI: $uri")
            notificationsCenter.showNotification(
                channelName = channelName,
                channelId = channelId,
                notificationId = "Backup restore failure".hashCode()
            ) {
                text = getString(R.string.failed_to_restore_cant_access_file)
            }
            return@withContext
        }

        // Read ZIP header to verify file format
        val bufferedStream = inputStream.buffered()
        bufferedStream.mark(8192)
        val header = ByteArray(4)
        val bytesRead = try { bufferedStream.read(header) } catch (e: Exception) { -1 }

        if (bytesRead == 4) {
            val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
            if (!isZip) {
                Timber.e("restoreData: File is not a valid ZIP file")
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore failure - invalid format".hashCode()
                ) {
                    removeProgressBar()
                    text = "Invalid backup file format. Expected ZIP file."
                }
                return@withContext
            }
        }

        try { bufferedStream.reset() } catch (e: Exception) {
            Timber.e(e, "restoreData: Failed to reset stream")
        }

        suspend fun mergeToDatabase(dbInputStream: InputStream) {
            tryAsResponse {
                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.loading_database)
                }

                val tempDbFile = File(context.cacheDir, "temp_restore_database.db")
                try {
                    tempDbFile.outputStream().use { output -> dbInputStream.copyTo(output) }
                    Timber.d("mergeToDatabase: Wrote database to temp file, size: ${tempDbFile.length()}")
                } catch (e: Exception) {
                    Timber.e(e, "mergeToDatabase: Failed to write temp database file")
                    throw e
                }

                val backupDatabase = object {
                    val newDatabase = try {
                        AppDatabase.createRoom(context, tempDbFile.absolutePath)
                    } catch (e: Exception) {
                        Timber.e(e, "mergeToDatabase: Failed to open Room database")
                        tempDbFile.delete()
                        throw e
                    }
                    val bookChapters = BookChaptersRepository(chapterDao = newDatabase.chapterDao())
                    val chapterBody = ChapterBodyRepository(
                        chapterBodyDao = newDatabase.chapterBodyDao(),
                        appDatabase = newDatabase,
                        chapterTranslationDao = newDatabase.chapterTranslationDao(),
                        bookChaptersRepository = bookChapters,
                        downloaderRepository = downloaderRepository
                    )
                    val libraryBooks = LibraryBooksRepository(
                        libraryDao = newDatabase.libraryDao(),
                        chapterDao = newDatabase.chapterDao(),
                        chapterBodyDao = newDatabase.chapterBodyDao(),
                        chapterTranslationDao = newDatabase.chapterTranslationDao(),
                        appDatabase = newDatabase,
                        context = context,
                        appFileResolver = appFileResolver,
                        appCoroutineScope = appCoroutineScope
                    )
                    fun close() = newDatabase.closeDatabase()
                    fun delete() {
                        try {
                            newDatabase.clearDatabase()
                            tempDbFile.delete()
                            File(tempDbFile.absolutePath + "-shm").delete()
                            File(tempDbFile.absolutePath + "-wal").delete()
                        } catch (e: Exception) {
                            Timber.e(e, "mergeToDatabase: Error cleaning up temp database")
                        }
                    }
                }

                // KEY FIX: Only restore books that are actually IN the library (inLibrary=true).
                // Books with inLibrary=false were previously deleted and should NOT be restored.
                val allBooksFromBackup = backupDatabase.libraryBooks.getAll()
                Timber.d("mergeToDatabase: Backup contains ${allBooksFromBackup.size} total books")

                val libraryBooksToRestore = allBooksFromBackup.filter { it.inLibrary }
                Timber.d("mergeToDatabase: Restoring ${libraryBooksToRestore.size} library books (skipping ${allBooksFromBackup.size - libraryBooksToRestore.size} non-library books)")

                val validBooks = libraryBooksToRestore.filter { book ->
                    book.url.matches("""^(https?|local)://.*""".toRegex())
                }

                if (validBooks.size < libraryBooksToRestore.size) {
                    Timber.w("mergeToDatabase: Filtered ${libraryBooksToRestore.size - validBooks.size} invalid URLs")
                }

                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.adding_books)
                }

                // Insert only valid library books
                try {
                    appRepository.libraryBooks.insertReplace(validBooks)
                    Timber.d("mergeToDatabase: Inserted ${validBooks.size} books")
                } catch (e: Exception) {
                    Timber.e(e, "mergeToDatabase: Bulk insert failed, trying individual")
                    validBooks.forEach { book ->
                        try { appRepository.libraryBooks.insertReplace(listOf(book)) }
                        catch (bookError: Exception) {
                            Timber.w(bookError, "Failed to insert book: ${book.title}")
                        }
                    }
                }

                // Get URLs of the books we actually restored to filter chapters
                val restoredBookUrls = validBooks.map { it.url }.toSet()

                val allChapters = backupDatabase.bookChapters.getAll()
                // Only restore chapters for books we actually restored
                val validChapters = allChapters.filter { chapter ->
                    chapter.bookUrl in restoredBookUrls &&
                            chapter.url.matches("""^(https?|local)://.*""".toRegex())
                }
                Timber.d("mergeToDatabase: Restoring ${validChapters.size} chapters (of ${allChapters.size} total in backup)")

                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.adding_chapters)
                }

                try {
                    appRepository.bookChapters.insert(validChapters)
                } catch (e: Exception) {
                    validChapters.forEach { chapter ->
                        try { appRepository.bookChapters.insert(listOf(chapter)) }
                        catch (chapterError: Exception) {
                            Timber.w(chapterError, "Failed to insert chapter: ${chapter.title}")
                        }
                    }
                }

                val restoredChapterUrls = validChapters.map { it.url }.toSet()
                val allBodies = backupDatabase.chapterBody.getAll()
                // Only restore bodies for chapters we actually restored
                val validBodies = allBodies.filter { it.url in restoredChapterUrls }
                Timber.d("mergeToDatabase: Restoring ${validBodies.size} chapter bodies (of ${allBodies.size} total in backup)")

                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.adding_chapters_text)
                }

                try {
                    appRepository.chapterBody.insertReplace(validBodies)
                } catch (e: Exception) {
                    validBodies.forEach { body ->
                        try { appRepository.chapterBody.insertReplace(listOf(body)) }
                        catch (bodyError: Exception) {
                            Timber.w(bodyError, "Failed to insert chapter body")
                        }
                    }
                }

                backupDatabase.close()
                backupDatabase.delete()
                Timber.d("mergeToDatabase: Database merge completed successfully")

            }.onError {
                Timber.e(it.exception, "mergeToDatabase: Failed to merge database")
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore failure - invalid database".hashCode()
                ) {
                    removeProgressBar()
                    text = getString(R.string.failed_to_restore_invalid_backup_database) +
                            ": ${it.exception.message?.take(100)}"
                }
            }.onSuccess {
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore success".hashCode()
                ) {
                    title = getString(R.string.backup_restored)
                }
            }
        }

        fun mergeToBookFolder(entry: ZipEntry, entryInputStream: InputStream) {
            try {
                val file = File(appRepository.settings.folderBooks.parentFile, entry.name)
                if (file.isDirectory) return
                file.parentFile?.mkdirs()
                if (file.parentFile?.exists() != true) {
                    Timber.w("mergeToBookFolder: Cannot create parent dir for ${entry.name}")
                    return
                }
                file.outputStream().use { output ->
                    entryInputStream.copyTo(output)
                }
            } catch (e: Exception) {
                Timber.e(e, "mergeToBookFolder: Error processing entry: ${entry.name}")
            }
        }

        notificationsCenter.modifyNotification(
            notificationBuilder,
            notificationId = notificationId
        ) {
            text = getString(R.string.adding_images)
        }

        try {
            ZipInputStream(bufferedStream).use { zipStream ->
                generateSequence {
                    try { zipStream.nextEntry } catch (e: Exception) { null }
                }
                    .filterNotNull()
                    .filterNot { it.isDirectory }
                    .forEach { entry ->
                        try {
                            when {
                                entry.name == "database.sqlite3" -> mergeToDatabase(zipStream)
                                entry.name.startsWith("books/") -> mergeToBookFolder(entry, zipStream)
                                else -> Timber.w("restoreData: Skipping unknown entry: ${entry.name}")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "restoreData: Error processing entry ${entry.name}")
                        }
                        zipStream.closeEntry()
                    }
            }
        } catch (e: Exception) {
            Timber.e(e, "restoreData: Failed to read ZIP file")
            notificationsCenter.showNotification(
                channelName = channelName,
                channelId = channelId,
                notificationId = "Backup restore failure - invalid zip".hashCode()
            ) {
                removeProgressBar()
                text = "Failed to read backup file: ${e.message}"
            }
            return@withContext
        }

        inputStream.closeQuietly()
        notificationsCenter.modifyNotification(
            notificationBuilder,
            notificationId = notificationId
        ) {
            removeProgressBar()
            text = getString(R.string.data_restored)
        }
    }
}