package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.cinematic_video.CinematicFfmpegAssetManager
import my.noveldokusha.cinematic_video.CinematicVideoException
import my.noveldokusha.cinematic_video.CinematicVideoRenderRequest
import my.noveldokusha.cinematic_video.CinematicVideoRenderer
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.strings.R as StringsR
import timber.log.Timber
import java.io.File

/** Renders the exact WAV + timeline pair produced by the immediately preceding audio worker. */
class TtsCinematicVideoWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CinematicVideoEntryPoint {
        fun appPreferences(): AppPreferences
        fun notificationsCenter(): NotificationsCenter
    }

    override suspend fun doWork(): Result {
        val app = EntryPointAccessors.fromApplication(
            context.applicationContext,
            CinematicVideoEntryPoint::class.java,
        )
        val prefs = app.appPreferences()
        val notifications = app.notificationsCenter()
        val request = readRequest() ?: return Result.failure()
        val notification = TtsAudioExportNotification(
            chapterTitle = request.chapterTitle,
            workRequestId = id.toString(),
            context = context,
            notificationsCenter = notifications,
        )

        val jobId = request.jobId
        var outputUri: Uri? = null
        val workDir = File(context.cacheDir, "tts_cinematic/$jobId").apply { mkdirs() }

        try {
            setForegroundSafely(notification)
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.RUNNING,
                    progress = 70,
                    message = "Rendering cinematic video…",
                )
            }
            notification.updateProgress(70)

            val novelFolder = resolveNovelFolder(request.outputDirectoryUri, request.novelTitle)
                ?: throw CinematicVideoException("NoveLA output folder could not be resolved")

            val sourceSuffix = when (request.source) {
                TtsAudioSource.ORIGINAL -> context.getString(StringsR.string.tts_audio_file_suffix_original)
                TtsAudioSource.TRANSLATED -> context.getString(StringsR.string.tts_audio_file_suffix_translated)
                TtsAudioSource.ASK_EVERY_TIME -> ""
            }
            val baseName = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)}"
            val audioName = if (sourceSuffix.isBlank()) "$baseName.wav" else "$baseName $sourceSuffix.wav"
            val timelineName = if (sourceSuffix.isBlank()) "$baseName.timeline.json" else "$baseName $sourceSuffix.timeline.json"
            val videoName = if (sourceSuffix.isBlank()) "$baseName.mp4" else "$baseName $sourceSuffix.mp4"

            val audioUri = findChild(novelFolder, audioName)
                ?: throw CinematicVideoException("Generated WAV was not found: $audioName")
            val timelineUri = findChild(novelFolder, timelineName)
                ?: throw CinematicVideoException("Generated timeline JSON was not found: $timelineName")

            val stagedAudio = File(workDir, audioName)
            val stagedTimeline = File(workDir, timelineName)
            copyUriToFile(audioUri, stagedAudio)
            copyUriToFile(timelineUri, stagedTimeline)

            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(progress = 74, message = "Rendering cinematic video…")
            }
            notification.updateProgress(74)

            val ffmpeg = CinematicFfmpegAssetManager(context).prepare(workDir)
            val stagedOutput = File(workDir, videoName)

            CinematicVideoRenderer().render(
                request = CinematicVideoRenderRequest(
                    audioFile = stagedAudio,
                    timelineFile = stagedTimeline,
                    outputFile = stagedOutput,
                    workingDirectory = workDir,
                    ffmpegDirectory = ffmpeg.parentFile ?: workDir,
                ),
            ) { fraction ->
                val percent = (74 + (fraction * 25f)).toInt().coerceIn(74, 99)
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(progress = percent, message = "Rendering cinematic video…")
                }
                notification.updateProgress(percent)
            }

            deleteChildIfPresent(novelFolder, videoName)
            outputUri = DocumentsContract.createDocument(
                context.contentResolver,
                novelFolder,
                MIME_MP4,
                videoName,
            ) ?: throw CinematicVideoException("Could not create MP4 in the selected NoveLA folder")
            copyFileToUri(stagedOutput, outputUri!!)

            val displayName = queryDisplayName(outputUri!!) ?: videoName
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.SUCCESS,
                    progress = 100,
                    message = "",
                    displayName = displayName,
                    documentUri = outputUri.toString(),
                )
            }
            notification.updateProgress(100)
            notification.showComplete(displayName, outputUri)
            workDir.deleteRecursively()
            return Result.success()
        } catch (e: CancellationException) {
            outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            workDir.deleteRecursively()
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(status = TtsAudioJobStatus.CANCELLED, message = "Cancelled")
            }
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsCinematicVideo: failed for $jobId")
            outputUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            workDir.deleteRecursively()
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.FAILED,
                    message = e.message ?: "Video rendering failed",
                )
            }
            notification.showError(e.message ?: "Video rendering failed")
            return Result.failure()
        }
    }

    private fun setForegroundSafely(notification: TtsAudioExportNotification) {
        try {
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            setForeground(
                ForegroundInfo(
                    notification.notificationId,
                    notification.foregroundNotification(),
                    foregroundType,
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "TtsCinematicVideo: setForeground failed")
        }
    }

    private suspend fun resolveNovelFolder(outputDirectoryUri: String, novelTitle: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = Uri.parse(outputDirectoryUri)
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val wrapperId = findOrCreateDirectoryDocId(
                    treeUri,
                    rootDocId,
                    TtsAudioExportWorker.WRAPPER_FOLDER_NAME,
                ) ?: return@runCatching null
                val novelId = findOrCreateDirectoryDocId(
                    treeUri,
                    wrapperId,
                    sanitize(novelTitle, "novel"),
                ) ?: return@runCatching null
                DocumentsContract.buildDocumentUriUsingTree(treeUri, novelId)
            }.getOrNull()
        }

    private fun findOrCreateDirectoryDocId(treeUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    cursor.getString(nameIndex).equals(name, ignoreCase = true)
                ) return cursor.getString(idIndex)
            }
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )?.let(DocumentsContract::getDocumentId)
        }.getOrNull()
    }

    private fun findChild(parent: Uri, displayName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == displayName) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(
                        parent,
                        cursor.getString(idIndex),
                    )
                }
            }
            null
        }
    }

    private fun deleteChildIfPresent(parent: Uri, displayName: String) {
        findChild(parent, displayName)?.let {
            runCatching { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun copyUriToFile(uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
        } ?: throw CinematicVideoException("Unable to read $uri")
    }

    private fun copyFileToUri(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER) }
        } ?: throw CinematicVideoException("Unable to write $uri")
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sanitize(name: String, fallback: String = "chapter"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }

    private fun readRequest(): VideoRequest? {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return null
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE) ?: return null
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: return null
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, 0)
        val source = runCatching {
            TtsAudioSource.valueOf(inputData.getString(KEY_SOURCE) ?: return null)
        }.getOrNull() ?: return null
        val outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null
        return VideoRequest(
            jobId = jobId,
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            source = source,
            outputDirectoryUri = outputDirectoryUri,
        )
    }

    private data class VideoRequest(
        val jobId: String,
        val novelTitle: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val source: TtsAudioSource,
        val outputDirectoryUri: String,
    )

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_NOVEL_TITLE = "novel_title"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_SOURCE = "source"
        const val KEY_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val MIME_MP4 = "video/mp4"
        private const val DEFAULT_BUFFER = 128 * 1024
    }
}