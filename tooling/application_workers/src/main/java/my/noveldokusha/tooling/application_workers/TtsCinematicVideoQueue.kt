package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsExportMode
import java.util.UUID

/** Single-click cinematic export: reuse a valid WAV+timeline, otherwise synthesize them then render MP4. */
object TtsCinematicVideoQueue {
    private const val WORK_PREFIX = "tts-cinematic-video"
    const val VIDEO_TAG = "tts-cinematic-video"
    private const val WRAPPER_FOLDER_NAME = "NoveLA Exports"
    private const val MIME_WAV = "audio/wav"
    private const val MIME_JSON = "application/json"

    fun enqueue(
        context: Context,
        appPreferences: AppPreferences,
        request: TtsAudioExportRequest,
    ): UUID {
        require(request.exportMode == TtsExportMode.CINEMATIC_VIDEO) {
            "TtsCinematicVideoQueue requires CINEMATIC_VIDEO mode"
        }

        val generationJobId = "${request.jobId}::${UUID.randomUUID()}"
        val videoRequest = OneTimeWorkRequestBuilder<TtsCinematicVideoWorker>()
            .setInputData(
                workDataOf(
                    TtsCinematicVideoWorker.KEY_JOB_ID to generationJobId,
                    TtsCinematicVideoWorker.KEY_NOVEL_TITLE to request.novelTitle,
                    TtsCinematicVideoWorker.KEY_CHAPTER_TITLE to request.chapterTitle,
                    TtsCinematicVideoWorker.KEY_CHAPTER_INDEX to request.chapterIndex,
                    TtsCinematicVideoWorker.KEY_SOURCE to request.source.name,
                    TtsCinematicVideoWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                )
            )
            .addTag(VIDEO_TAG)
            // Existing TtsAudioQueue reconciliation already knows this persistent job model;
            // keep the video worker visible to that reconciler as well.
            .addTag(TtsAudioQueue.AUDIO_TAG)
            .build()

        val state = TtsAudioJobState(
            chapterUrl = request.chapterUrl,
            novelUrl = request.novelUrl,
            chapterTitle = request.chapterTitle,
            source = request.source,
            status = TtsAudioJobStatus.QUEUED,
            cinematicVideo = true,
            message = "Preparing cinematic video…",
            progress = 0,
            workRequestId = videoRequest.id.toString(),
        )
        TtsAudioQueue.updateState(appPreferences, generationJobId) { state }

        val work = WorkManager.getInstance(context)
        if (hasReusableAudioAndTimeline(context, request)) {
            work.beginUniqueWork(
                uniqueWorkName(request.jobId),
                ExistingWorkPolicy.REPLACE,
                videoRequest,
            ).enqueue()
        } else {
            val audioRequest = TtsAudioExportWorker.createWorkRequest(request, generationJobId)
            work.beginUniqueWork(
                uniqueWorkName(request.jobId),
                ExistingWorkPolicy.REPLACE,
                audioRequest,
            ).then(videoRequest).enqueue()
        }

        return videoRequest.id
    }

    fun cancel(context: Context, workRequestId: String) {
        runCatching {
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workRequestId))
        }
    }

    private fun uniqueWorkName(logicalJobId: String): String = "$WORK_PREFIX-$logicalJobId"

    /**
     * Only skips synthesis when both expected artifacts exist and are minimally sane.
     * Invalid/empty artifacts intentionally go through the normal TTS export worker.
     */
    private fun hasReusableAudioAndTimeline(
        context: Context,
        request: TtsAudioExportRequest,
    ): Boolean = runCatching {
        val treeUri = Uri.parse(request.outputDirectoryUri)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val wrapperId = findChildDirectory(context, treeUri, rootId, WRAPPER_FOLDER_NAME) ?: return false
        val novelId = findChildDirectory(context, treeUri, wrapperId, sanitize(request.novelTitle, "novel")) ?: return false
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, novelId)
        val suffix = when (request.source) {
            my.noveldokusha.core.appPreferences.TtsAudioSource.ORIGINAL -> "original"
            my.noveldokusha.core.appPreferences.TtsAudioSource.TRANSLATED -> "translated"
            else -> ""
        }
        val base = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)}"
        val audioName = if (suffix.isBlank()) "$base.wav" else "$base $suffix.wav"
        val timelineName = "$base${if (suffix.isBlank()) "" else " $suffix"}.timeline.json"
        val audio = findChild(context, parent, audioName) ?: return false
        val timeline = findChild(context, parent, timelineName) ?: return false
        val audioLength = context.contentResolver.openAssetFileDescriptor(audio, "r")?.use { it.length } ?: -1L
        if (audioLength <= 44L) return false
        val json = context.contentResolver.openInputStream(timeline)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return false
        val root = org.json.JSONObject(json)
        root.optJSONObject("audio")?.optLong("durationMs", 0L)?.let { duration -> duration > 0L } == true &&
            root.optJSONArray("paragraphs")?.length()?.let { it > 0 } == true
    }.getOrDefault(false)

    private fun findChildDirectory(
        context: Context,
        treeUri: Uri,
        parentId: String,
        name: String,
    ): String? = findChild(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId), name)
        ?.let(DocumentsContract::getDocumentId)

    private fun findChild(context: Context, parent: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        return context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val display = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(display) == name) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(id))
                }
            }
            null
        }
    }

    private fun sanitize(name: String, fallback: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }
}
