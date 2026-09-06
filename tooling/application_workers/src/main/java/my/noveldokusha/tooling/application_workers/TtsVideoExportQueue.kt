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
import timber.log.Timber

object TtsVideoExportQueue {
    const val VIDEO_TAG = "tts-video-export"
    private const val WORK_PREFIX = "tts-video-export"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    fun enqueueFromJob(context: Context, job: TtsAudioJobState) {
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()
        val entry = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.entries.firstOrNull { (_, value) ->
            value.chapterUrl == job.chapterUrl && value.novelUrl == job.novelUrl && value.source == job.source &&
                value.audioUri == job.audioUri && value.timelineUri == job.timelineUri
        } ?: return
        enqueue(context, prefs, entry.key, entry.value)
    }

    fun enqueue(context: Context, prefs: AppPreferences, jobId: String, job: TtsAudioJobState) {
        if (job.status != TtsAudioJobStatus.SUCCESS || !job.phase.equals("AUDIO", true)) return
        if (job.audioUri.isBlank() || job.timelineUri.isBlank() || job.outputDirectoryUri.isBlank()) return
        val request = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
            .setInputData(workDataOf(
                TtsVideoExportWorker.KEY_JOB_ID to jobId,
                TtsVideoExportWorker.KEY_AUDIO_URI to job.audioUri,
                TtsVideoExportWorker.KEY_TIMELINE_URI to job.timelineUri,
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
                    deleteExpectedVideo(context, job)
                    val u = job.copy(status = TtsAudioJobStatus.SUCCESS, phase = "AUDIO", progress = 100,
                        documentUri = job.audioUri, workRequestId = "", videoSizeBytes = 0L, message = "")
                    if (u != job) { current[jobId] = u; changed = true }
                }
            }
        }
        if (changed) prefs.TTS_AUDIO_DOWNLOAD_JOBS.value = current
    }

    private fun workName(jobId: String) = "$WORK_PREFIX-$jobId"

    private suspend fun deleteExpectedVideo(context: Context, job: TtsAudioJobState) = withContext(Dispatchers.IO) {
        runCatching {
            val audioUri = Uri.parse(job.audioUri)
            val tree = Uri.parse(job.outputDirectoryUri)
            val parentId = context.contentResolver.query(audioUri, arrayOf(DocumentsContract.Document.COLUMN_PARENT_DOCUMENT_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_PARENT_DOCUMENT_ID)) else null
            } ?: return@runCatching
            val name = context.contentResolver.query(audioUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
            } ?: job.displayName
            val videoName = name.removeSuffix(".wav") + ".mp4"
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            context.contentResolver.query(children, null, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val n = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) if (c.getString(n).equals(videoName, true)) {
                    DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(id)))
                    break
                }
            }
        }.onFailure { Timber.w(it, "TtsVideo: stale MP4 cleanup failed") }
    }
}
