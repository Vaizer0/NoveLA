package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import timber.log.Timber
import java.util.UUID

/** Owns only the resumable cinematic-video stage. WAV+timeline remain untouched. */
object TtsVideoExportQueue {
    const val VIDEO_TAG = "tts-video-export"
    private const val WORK_PREFIX = "tts-video-export"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    fun enqueueFromJob(context: Context, job: TtsAudioJobState) {
        if (job.status != TtsAudioJobStatus.SUCCESS || !job.phase.equals("AUDIO", true)) return
        if (job.audioUri.isBlank() || job.timelineUri.isBlank() || job.outputDirectoryUri.isBlank()) return
        enqueue(context, appPreferences(context), job)
    }

    fun enqueue(context: Context, appPreferences: AppPreferences, job: TtsAudioJobState) {
        if (job.status != TtsAudioJobStatus.SUCCESS || !job.phase.equals("AUDIO", true)) return
        if (job.audioUri.isBlank() || job.timelineUri.isBlank() || job.outputDirectoryUri.isBlank()) return

        val work = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
            .setInputData(
                workDataOf(
                    TtsVideoExportWorker.KEY_JOB_ID to stableJobId(job),
                    TtsVideoExportWorker.KEY_AUDIO_URI to job.audioUri,
                    TtsVideoExportWorker.KEY_TIMELINE_URI to job.timelineUri,
                    TtsVideoExportWorker.KEY_OUTPUT_DIRECTORY_URI to job.outputDirectoryUri,
                    TtsVideoExportWorker.KEY_NOVEL_TITLE to job.novelUrl,
                    TtsVideoExportWorker.KEY_CHAPTER_TITLE to job.chapterTitle,
                    TtsVideoExportWorker.KEY_DISPLAY_NAME to job.displayName,
                    TtsVideoExportWorker.KEY_NOVEL_URL to job.novelUrl,
                    TtsVideoExportWorker.KEY_CHAPTER_URL to job.chapterUrl,
                    TtsVideoExportWorker.KEY_CHAPTER_INDEX to 0,
                    TtsVideoExportWorker.KEY_SOURCE to job.source.name,
                )
            )
            .addTag(VIDEO_TAG)
            .build()

        val jobId = stableJobId(job)
        TtsAudioQueue.updateState(appPreferences, jobId) {
            job.copy(
                status = TtsAudioJobStatus.QUEUED,
                phase = "VIDEO",
                progress = 0,
                documentUri = job.audioUri,
                workRequestId = work.id.toString(),
                videoSizeBytes = 0L,
                message = "",
            )
        }

        WorkManager.getInstance(context)
            .beginUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, work)
            .enqueue()
    }

    /** Called from TtsAudioQueue.reconcile so process death during VIDEO is resumable. */
    suspend fun reconcile(context: Context, appPreferences: AppPreferences) {
        val infos = runCatching {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).getWorkInfosByTag(VIDEO_TAG).get()
            }
        }.getOrNull() ?: return
        val byId = infos.associateBy { it.id.toString() }

        val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
        var changed = false
        for ((jobId, job) in current.toList()) {
            if (!job.phase.equals("VIDEO", true)) continue
            val info = byId[job.workRequestId]
            when (info?.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED -> Unit
                WorkInfo.State.SUCCEEDED -> {
                    val updated = job.copy(status = TtsAudioJobStatus.SUCCESS, phase = "VIDEO", progress = 100)
                    if (updated != job) {
                        current[jobId] = updated
                        changed = true
                    }
                }
                WorkInfo.State.CANCELLED,
                WorkInfo.State.FAILED,
                null -> {
                    deleteExpectedVideo(context, job)
                    val restored = job.copy(
                        status = TtsAudioJobStatus.SUCCESS,
                        phase = "AUDIO",
                        progress = 100,
                        documentUri = job.audioUri,
                        displayName = job.displayName,
                        workRequestId = "",
                        videoSizeBytes = 0L,
                        message = "",
                    )
                    if (restored != job) {
                        current[jobId] = restored
                        changed = true
                    }
                }
            }
        }
        if (changed) appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
    }

    private fun appPreferences(context: Context): AppPreferences =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            EntryPointAccess::class.java,
        ).appPreferences()

    private fun stableJobId(job: TtsAudioJobState): String =
        "${TtsAudioJobRequestIdPrefix(job)}"

    private fun TtsAudioJobRequestIdPrefix(job: TtsAudioJobState): String =
        TtsAudioExportRequestId.id(job.chapterUrl, job.source, job.novelUrl)

    private fun workName(jobId: String): String = "$WORK_PREFIX-$jobId"

    private suspend fun deleteExpectedVideo(context: Context, job: TtsAudioJobState) = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(job.outputDirectoryUri)
        runCatching {
            val root = DocumentsContract.getTreeDocumentId(treeUri)
            val wrapper = findDirectory(context, treeUri, root, "NoveLA Audio") ?: return@runCatching
            val novel = findDirectory(context, treeUri, wrapper, job.novelUrl) ?: return@runCatching
            val audioName = queryName(context, Uri.parse(job.audioUri)) ?: return@runCatching
            val videoName = audioName.removeSuffix(".wav").plus(".mp4")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, novel)
            context.contentResolver.query(children, null, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn).equals(videoName, true)) {
                        DocumentsContract.deleteDocument(
                            context.contentResolver,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn)),
                        )
                        break
                    }
                }
            }
        }.onFailure { Timber.w(it, "TtsVideo: failed to clean stale MP4") }
    }

    private fun findDirectory(context: Context, treeUri: Uri, parentId: String, name: String): String? =
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId),
            null, null, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    cursor.getString(nameCol).equals(name, true)
                ) return@use cursor.getString(idCol)
            }
            null
        }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
        }
}

private object TtsAudioExportRequestId {
    fun id(chapterUrl: String, source: my.noveldokusha.core.appPreferences.TtsAudioSource, novelUrl: String): String =
        "video-${source.name.lowercase()}-${sha(chapterUrl + "|" + novelUrl)}"

    private fun sha(value: String): String = value.hashCode().toString(16)
}
