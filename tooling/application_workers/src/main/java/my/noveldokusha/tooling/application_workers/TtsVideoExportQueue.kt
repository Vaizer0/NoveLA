package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

object TtsVideoExportQueue {
    const val VIDEO_TAG = "tts-video-export"
    private const val WORK_PREFIX = "tts-video-export"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    suspend fun enqueueFromJob(context: Context, job: TtsAudioJobState) {
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()
        val entry = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.entries.firstOrNull { (_, value) ->
            value.chapterUrl == job.chapterUrl && value.novelUrl == job.novelUrl && value.source == job.source &&
                value.audioUri == job.audioUri && value.timelineUri == job.timelineUri
        } ?: return
        val parentDirectoryUri = findParentDirectoryUri(context, Uri.parse(entry.value.outputDirectoryUri), Uri.parse(entry.value.audioUri)) ?: return
        enqueue(context, prefs, entry.key, entry.value, parentDirectoryUri.toString())
    }

    fun enqueue(context: Context, prefs: AppPreferences, jobId: String, job: TtsAudioJobState, parentDirectoryUri: String) {
        if (job.status != TtsAudioJobStatus.SUCCESS || !job.phase.equals("AUDIO", true)) return
        if (job.audioUri.isBlank() || job.timelineUri.isBlank() || job.outputDirectoryUri.isBlank() || parentDirectoryUri.isBlank()) return
        val request = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
            .setInputData(workDataOf(
                TtsVideoExportWorker.KEY_JOB_ID to jobId,
                TtsVideoExportWorker.KEY_AUDIO_URI to job.audioUri,
                TtsVideoExportWorker.KEY_TIMELINE_URI to job.timelineUri,
                TtsVideoExportWorker.KEY_PARENT_DIRECTORY_URI to parentDirectoryUri,
                TtsVideoExportWorker.KEY_OUTPUT_DIRECTORY_URI to job.outputDirectoryUri,
                TtsVideoExportWorker.KEY_CHAPTER_TITLE to job.chapterTitle,
                TtsVideoExportWorker.KEY_DISPLAY_NAME to job.displayName,
            ))
            .addTag(VIDEO_TAG)
            .build()
        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(status = TtsAudioJobStatus.QUEUED, phase = "VIDEO", progress = 0, documentUri = job.audioUri,
                workRequestId = request.id.toString(), videoSizeBytes = 0L, message = "")
        }
        WorkManager.getInstance(context).beginUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, request).enqueue()
    }

    suspend fun reconcile(context: Context, prefs: AppPreferences) {
        val infos = runCatching { withContext(Dispatchers.IO) { WorkManager.getInstance(context).getWorkInfosByTag(VIDEO_TAG).get() } }.getOrNull() ?: return
        val byId = infos.associateBy { it.id.toString() }
        val current = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
        var changed = false
        for ((jobId, job) in current.toList()) {
            if (!job.phase.equals("VIDEO", true)) continue
            when (byId[job.workRequestId]?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> Unit
                WorkInfo.State.SUCCEEDED -> {
                    val u = job.copy(status = TtsAudioJobStatus.SUCCESS, phase = "VIDEO", progress = 100, workRequestId = "")
                    if (u != job) { current[jobId] = u; changed = true }
                }
                WorkInfo.State.CANCELLED, WorkInfo.State.FAILED, null -> {
                    val u = job.copy(status = TtsAudioJobStatus.SUCCESS, phase = "AUDIO", progress = 100,
                        documentUri = job.audioUri, workRequestId = "", videoSizeBytes = 0L, message = "")
                    if (u != job) { current[jobId] = u; changed = true }
                }
            }
        }
        if (changed) prefs.TTS_AUDIO_DOWNLOAD_JOBS.value = current
    }

    private suspend fun findParentDirectoryUri(context: Context, treeUri: Uri, targetUri: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val targetId = DocumentsContract.getDocumentId(targetUri)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val stack = ArrayDeque<Pair<String, Int>>()
            val visited = HashSet<String>()
            stack.add(rootId to 0)
            while (stack.isNotEmpty()) {
                val (parentId, depth) = stack.removeLast()
                if (depth > 8 || !visited.add(parentId)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idCol)
                        if (childId == targetId) {
                            return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
                        }
                        if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.add(childId to depth + 1)
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun workName(jobId: String) = "$WORK_PREFIX-$jobId"
}
