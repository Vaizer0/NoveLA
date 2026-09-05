package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsExportMode
import java.util.UUID

/** Single-click cinematic export: reuse existing downloaded audio whenever possible. */
object TtsCinematicVideoQueue {
    private const val WORK_PREFIX = "tts-cinematic-video"
    const val VIDEO_TAG = "tts-cinematic-video"
    private const val WRAPPER_FOLDER_NAME = "NoveLA Exports"

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
                    TtsCinematicVideoWorker.KEY_NOVEL_URL to request.novelUrl,
                    TtsCinematicVideoWorker.KEY_CHAPTER_URL to request.chapterUrl,
                    TtsCinematicVideoWorker.KEY_CHAPTER_INDEX to request.chapterIndex,
                    TtsCinematicVideoWorker.KEY_SOURCE to request.source.name,
                    TtsCinematicVideoWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                    TtsCinematicVideoWorker.KEY_TRANSLATION_SOURCE_LANG to request.translationSourceLang,
                    TtsCinematicVideoWorker.KEY_TRANSLATION_TARGET_LANG to request.translationTargetLang,
                )
            )
            .addTag(VIDEO_TAG)
            .addTag(TtsAudioQueue.AUDIO_TAG)
            .build()

        TtsAudioQueue.updateState(appPreferences, generationJobId) {
            TtsAudioJobState(
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
        }

        val work = WorkManager.getInstance(context)
        if (hasReusableAudio(context, request)) {
            work.beginUniqueWork(
                uniqueWorkName(request.jobId),
                ExistingWorkPolicy.REPLACE,
                videoRequest,
            ).enqueue()
        } else {
            work.beginUniqueWork(
                uniqueWorkName(request.jobId),
                ExistingWorkPolicy.REPLACE,
                createAudioRequest(request, generationJobId),
            ).then(videoRequest).enqueue()
        }

        return videoRequest.id
    }

    fun cancel(context: Context, workRequestId: String) {
        runCatching {
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workRequestId))
        }
    }

    private fun createAudioRequest(request: TtsAudioExportRequest, generationJobId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
            .setInputData(
                workDataOf(
                    TtsAudioExportWorker.KEY_JOB_ID to generationJobId,
                    TtsAudioExportWorker.KEY_NOVEL_TITLE to request.novelTitle,
                    TtsAudioExportWorker.KEY_NOVEL_URL to request.novelUrl,
                    TtsAudioExportWorker.KEY_CHAPTER_URL to request.chapterUrl,
                    TtsAudioExportWorker.KEY_CHAPTER_TITLE to request.chapterTitle,
                    TtsAudioExportWorker.KEY_CHAPTER_INDEX to request.chapterIndex,
                    TtsAudioExportWorker.KEY_SOURCE to request.source.name,
                    TtsAudioExportWorker.KEY_ENGINE_PACKAGE to request.enginePackage,
                    TtsAudioExportWorker.KEY_VOICE_ID to request.voiceId,
                    TtsAudioExportWorker.KEY_SPEED to request.speed,
                    TtsAudioExportWorker.KEY_PITCH to request.pitch,
                    TtsAudioExportWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                    TtsAudioExportWorker.KEY_FORMAT to request.format,
                    TtsAudioExportWorker.KEY_TRANSLATION_SOURCE_LANG to request.translationSourceLang,
                    TtsAudioExportWorker.KEY_TRANSLATION_TARGET_LANG to request.translationTargetLang,
                )
            )
            .addTag(TtsAudioQueue.AUDIO_TAG)
            .build()

    private fun uniqueWorkName(logicalJobId: String): String = "$WORK_PREFIX-$logicalJobId"

    /** Reuse any valid downloaded audio; a missing timeline is reconstructed without TTS synthesis. */
    private fun hasReusableAudio(
        context: Context,
        request: TtsAudioExportRequest,
    ): Boolean = runCatching {
        val treeUri = Uri.parse(request.outputDirectoryUri)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val wrapperUri = findChild(context, rootUri, WRAPPER_FOLDER_NAME) ?: return false
        val novelUri = findChild(context, wrapperUri, sanitize(request.novelTitle, "novel")) ?: return false
        val base = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle, "chapter")}"
        val suffix = when (request.source) {
            TtsAudioSource.ORIGINAL -> context.getString(my.noveldokusha.strings.R.string.tts_audio_file_suffix_original)
            TtsAudioSource.TRANSLATED -> context.getString(my.noveldokusha.strings.R.string.tts_audio_file_suffix_translated)
            TtsAudioSource.ASK_EVERY_TIME -> ""
        }
        val prefix = if (suffix.isBlank()) base else "$base $suffix"
        listOf("wav", "m4a", "mp3", "aac", "ogg", "opus").any { extension ->
            val uri = findChild(context, novelUri, "$prefix.$extension") ?: return@any false
            val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            length > 44L
        }
    }.getOrDefault(false)

    private fun findChild(context: Context, parent: Uri, name: String): Uri? {
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
