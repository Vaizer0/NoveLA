package my.noveldokusha.features.chapterslist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TranslationLangPair
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.tooling.application_workers.TtsAudioQueue
import my.noveldokusha.tooling.application_workers.TtsVideoExportQueue

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun ChaptersScreenChapterItem(
    chapterWithContext: ChapterWithContext,
    novelTitle: String,
    translatedTitle: String? = null,
    chapterSize: ChapterSize? = null,
    audioOriginalJob: TtsAudioJobState? = null,
    audioOriginalFileExists: Boolean = false,
    audioTranslatedJob: TtsAudioJobState? = null,
    audioTranslatedFileExists: Boolean = false,
    selected: Boolean,
    isLocalSource: Boolean,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onAudioOriginal: () -> Unit,
    onAudioTranslated: () -> Unit,
    translatedAudioAvailable: Boolean = true,
) {
    val chapter = chapterWithContext.chapter
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.5.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            SlimListItem(
                headlineContent = {
                    Text(translatedTitle ?: chapter.title, style = MaterialTheme.typography.bodyMedium)
                },
                supportingContent = chapterSize?.sizeBytes?.let {
                    { Text(formatBytes(it), style = MaterialTheme.typography.labelSmall) }
                },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (!isLocalSource) {
                            AnimatedTransition(targetState = chapterWithContext.downloaded) { downloaded ->
                                IconButton(onClick = onDownload) {
                                    Icon(
                                        if (downloaded) Icons.Filled.CloudDownload else Icons.Outlined.CloudDownload,
                                        null,
                                    )
                                }
                            }
                        }
                        ChapterAudioButton(
                            source = TtsAudioSource.ORIGINAL,
                            audioJob = audioOriginalJob,
                            audioFileExists = audioOriginalFileExists,
                            enabled = true,
                            novelTitle = novelTitle,
                            chapter = chapter,
                            onAudio = onAudioOriginal,
                        )
                        ChapterAudioButton(
                            source = TtsAudioSource.TRANSLATED,
                            audioJob = audioTranslatedJob,
                            audioFileExists = audioTranslatedFileExists,
                            enabled = translatedAudioAvailable || audioTranslatedJob != null,
                            novelTitle = novelTitle,
                            chapter = chapter,
                            onAudio = onAudioTranslated,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ChapterAudioButton(
    source: TtsAudioSource,
    audioJob: TtsAudioJobState?,
    audioFileExists: Boolean,
    enabled: Boolean,
    novelTitle: String,
    chapter: Chapter,
    onAudio: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var discoveredCheckpoint by remember(chapter.url, source) { mutableStateOf<DiscoveredAudioCheckpoint?>(null) }
    val audioLocation = AppPreferences(context).TTS_AUDIO_DOWNLOAD_LOCATION_URI.value

    LaunchedEffect(chapter.url, source, novelTitle, audioLocation, audioJob?.audioUri, audioJob?.timelineUri, audioJob?.documentUri) {
        val discovered = withContext(Dispatchers.IO) {
            discoverExistingArtifacts(context, chapter, novelTitle, source)
        }
        if (discovered != null) {
            discoveredCheckpoint = discovered
            val prefs = AppPreferences(context)
            TtsAudioQueue.updateState(prefs, discovered.jobId) { existing ->
                when {
                    discovered.job.documentUri.isNotBlank() -> {
                        (existing ?: discovered.job).copy(
                            status = TtsAudioJobStatus.SUCCESS,
                            phase = "VIDEO",
                            progress = 100,
                            documentUri = discovered.job.documentUri,
                            audioUri = existing?.audioUri?.ifBlank { discovered.job.audioUri } ?: discovered.job.audioUri,
                            timelineUri = existing?.timelineUri?.ifBlank { discovered.job.timelineUri } ?: discovered.job.timelineUri,
                            outputDirectoryUri = existing?.outputDirectoryUri?.ifBlank { discovered.job.outputDirectoryUri } ?: discovered.job.outputDirectoryUri,
                            displayName = existing?.displayName?.ifBlank { discovered.job.displayName } ?: discovered.job.displayName,
                            workRequestId = "",
                            videoStagingUri = "",
                            videoStagingComplete = false,
                            videoRecoveryAttempts = 0,
                            videoStopReason = "",
                            message = "",
                        )
                    }
                    existing == null -> discovered.job
                    else -> existing
                }
            }
        }
    }

    val effectiveJob = run {
        val discoveredVideo = discoveredCheckpoint?.job?.takeIf { it.documentUri.isNotBlank() }
        if (discoveredVideo != null) {
            (audioJob ?: discoveredVideo).copy(
                status = TtsAudioJobStatus.SUCCESS,
                phase = "VIDEO",
                progress = 100,
                documentUri = discoveredVideo.documentUri,
                audioUri = audioJob?.audioUri?.ifBlank { discoveredVideo.audioUri } ?: discoveredVideo.audioUri,
                timelineUri = audioJob?.timelineUri?.ifBlank { discoveredVideo.timelineUri } ?: discoveredVideo.timelineUri,
            )
        } else {
            audioJob ?: discoveredCheckpoint?.job
        }
    }
    val status = effectiveJob?.status
    val phase = effectiveJob?.phase?.uppercase()
    val running = effectiveJob != null && effectiveJob.isActive
    val effectiveAudioFileExists = audioFileExists ||
        (effectiveJob?.audioUri?.isNotBlank() == true && documentExists(context, effectiveJob.audioUri))
    val timelineExists = effectiveJob?.timelineUri?.isNotBlank() == true &&
        documentExists(context, effectiveJob.timelineUri)
    val videoExists = effectiveJob?.documentUri?.isNotBlank() == true &&
        documentExists(context, effectiveJob.documentUri)
    val audioReady = status == TtsAudioJobStatus.SUCCESS &&
        phase == "AUDIO" &&
        effectiveJob!!.audioUri.isNotBlank() &&
        effectiveAudioFileExists &&
        effectiveJob.timelineUri.isNotBlank() &&
        timelineExists
    val videoReady = status == TtsAudioJobStatus.SUCCESS &&
        phase == "VIDEO" &&
        effectiveJob!!.documentUri.isNotBlank() &&
        videoExists
    val clickable = enabled || effectiveJob != null

    fun startVideoGeneration() {
        val job = effectiveJob ?: return
        coroutineScope.launch {
            val prefs = AppPreferences(context)
            val discovered = discoveredCheckpoint
            if (discovered != null && discovered.jobId == makeDiscoveredJobId(context, chapter, source)) {
                TtsAudioQueue.updateState(prefs, discovered.jobId) { existing ->
                    existing ?: job
                }
                TtsVideoExportQueue.enqueue(
                    context = context,
                    prefs = prefs,
                    jobId = discovered.jobId,
                    job = job,
                    parentDirectoryUri = discovered.parentDirectoryUri,
                )
            } else {
                TtsVideoExportQueue.enqueueFromJob(context, job)
            }
        }
    }

    when {
        running -> {
            val p = effectiveJob!!.progress.coerceIn(0, 100)
            val c = if (phase == "VIDEO") Color(0xFFFF9800) else MaterialTheme.colorScheme.tertiary
            IconButton(onClick = {}) {
                Box {
                    CircularProgressIndicator(
                        progress = { p / 100f },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = c,
                    )
                    Text("$p", Modifier.align(Alignment.Center), fontSize = 9.sp, color = c)
                    Icon(
                        sourceFilledIcon(source),
                        null,
                        Modifier.align(Alignment.BottomEnd).size(12.dp),
                        tint = c,
                    )
                }
            }
        }
        videoReady -> {
            IconButton(onClick = {
                openDocument(context, effectiveJob!!.documentUri, "video/mp4")
            }) {
                Box {
                    Icon(sourceFilledIcon(source), null, tint = Color(0xFFFF9800))
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        Modifier.align(Alignment.BottomEnd).size(12.dp),
                        tint = Color(0xFFFF9800),
                    )
                }
            }
        }
        audioReady -> {
            val blue = Color(0xFF2196F3)
            IconButton(onClick = {
                startVideoGeneration()
            }) {
                Box {
                    Icon(sourceFilledIcon(source), null, tint = blue)
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        Modifier.align(Alignment.BottomEnd).size(12.dp),
                        tint = blue,
                    )
                }
            }
        }
        status == TtsAudioJobStatus.FAILED -> {
            IconButton(onClick = onAudio) {
                Box {
                    Icon(sourceFilledIcon(source), null, tint = MaterialTheme.colorScheme.error)
                    Icon(
                        Icons.Filled.Refresh,
                        null,
                        Modifier.align(Alignment.BottomEnd).size(12.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        else -> {
            IconButton(onClick = onAudio, enabled = clickable) {
                Icon(
                    sourceIdleIcon(source),
                    null,
                    tint = if (clickable) {
                        LocalContentColor.current
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

private data class DiscoveredAudioCheckpoint(
    val jobId: String,
    val job: TtsAudioJobState,
    val parentDirectoryUri: String,
)

private fun makeDiscoveredJobId(
    context: Context,
    chapter: Chapter,
    source: TtsAudioSource,
): String? {
    val prefs = AppPreferences(context)
    val pair = if (source == TtsAudioSource.TRANSLATED) {
        prefs.translationPairForBook(chapter.bookUrl)
    } else {
        TranslationLangPair()
    }
    return runCatching {
        TtsAudioExportRequest.makeJobId(
            chapter.bookUrl,
            chapter.url,
            source,
            pair.source,
            pair.target,
        )
    }.getOrNull()
}

private fun discoverExistingArtifacts(
    context: Context,
    chapter: Chapter,
    novelTitle: String,
    source: TtsAudioSource,
): DiscoveredAudioCheckpoint? = runCatching {
    val prefs = AppPreferences(context)
    val location = prefs.TTS_AUDIO_DOWNLOAD_LOCATION_URI.value
    if (location.isBlank()) return null

    val treeUri = Uri.parse(location)
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val audioRootId = findChildDirectoryId(context, treeUri, rootId, "NoveLA Audio") ?: return null
    val novelDirId = findChildDirectoryId(context, treeUri, audioRootId, sanitize(novelTitle, "novel")) ?: return null
    val novelDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, novelDirId)

    val base = "${chapter.position + 1} - ${sanitize(chapter.title)}"
    val suffix = when (source) {
        TtsAudioSource.ORIGINAL -> context.getString(my.noveldokusha.strings.R.string.tts_audio_file_suffix_original)
        TtsAudioSource.TRANSLATED -> context.getString(my.noveldokusha.strings.R.string.tts_audio_file_suffix_translated)
        TtsAudioSource.ASK_EVERY_TIME -> return null
    }
    val suffixPart = if (suffix.isBlank()) "" else " $suffix"
    val audioName = "$base$suffixPart.wav"
    val timelineName = "$base$suffixPart.timeline.json"
    val videoName = "$base$suffixPart.mp4"

    val files = queryChildren(context, treeUri, novelDirId)
    val audioUri = files[audioName]
        ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it).toString() }
        ?: ""
    val timelineUri = files[timelineName]
        ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it).toString() }
        ?: ""
    val videoUri = files[videoName]
        ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it).toString() }
        ?: ""

    val hasAudioCheckpoint = audioUri.isNotBlank() && timelineUri.isNotBlank() &&
        documentExists(context, audioUri) && documentExists(context, timelineUri)
    val hasVideoCheckpoint = videoUri.isNotBlank() && documentExists(context, videoUri)
    if (!hasAudioCheckpoint && !hasVideoCheckpoint) return null

    val pair = if (source == TtsAudioSource.TRANSLATED) {
        prefs.translationPairForBook(chapter.bookUrl)
    } else {
        TranslationLangPair()
    }
    val jobId = TtsAudioExportRequest.makeJobId(
        chapter.bookUrl,
        chapter.url,
        source,
        pair.source,
        pair.target,
    )
    val phase = if (hasVideoCheckpoint) "VIDEO" else "AUDIO"
    val job = TtsAudioJobState(
        chapterUrl = chapter.url,
        novelUrl = chapter.bookUrl,
        chapterTitle = chapter.title,
        source = source,
        status = TtsAudioJobStatus.SUCCESS,
        message = "",
        documentUri = videoUri,
        displayName = audioName,
        progress = 100,
        phase = phase,
        audioUri = audioUri,
        timelineUri = timelineUri,
        outputDirectoryUri = treeUri.toString(),
        videoSizeBytes = if (hasVideoCheckpoint) queryDocumentSize(context, videoUri) else 0L,
        workRequestId = "",
    )
    DiscoveredAudioCheckpoint(jobId, job, novelDirUri.toString())
}.getOrNull()

private fun findChildDirectoryId(
    context: Context,
    treeUri: Uri,
    parentId: String,
    name: String,
): String? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    context.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR &&
                cursor.getString(nameCol).equals(name, ignoreCase = true)
            ) return cursor.getString(idCol)
        }
    }
    return null
}

private fun queryChildren(
    context: Context,
    treeUri: Uri,
    parentId: String,
): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    context.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (cursor.moveToNext()) result[cursor.getString(nameCol)] = cursor.getString(idCol)
    }
    return result
}

private fun documentExists(context: Context, uriString: String): Boolean = runCatching {
    context.contentResolver.query(
        Uri.parse(uriString),
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { it.moveToFirst() } ?: false
}.getOrDefault(false)

private fun queryDocumentSize(context: Context, uriString: String): Long = runCatching {
    context.contentResolver.query(
        Uri.parse(uriString),
        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        if (sizeCol >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
    } ?: 0L
}.getOrDefault(0L)

private fun openDocument(context: Context, uriString: String, mime: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun sourceIdleIcon(source: TtsAudioSource): ImageVector = when (source) {
    TtsAudioSource.ORIGINAL -> Icons.Outlined.AudioFile
    TtsAudioSource.TRANSLATED -> Icons.Outlined.Translate
    TtsAudioSource.ASK_EVERY_TIME -> Icons.Outlined.GraphicEq
}

private fun sourceFilledIcon(source: TtsAudioSource): ImageVector = when (source) {
    TtsAudioSource.ORIGINAL -> Icons.Filled.AudioFile
    TtsAudioSource.TRANSLATED -> Icons.Filled.Translate
    TtsAudioSource.ASK_EVERY_TIME -> Icons.Filled.GraphicEq
}

private fun sanitize(name: String, fallback: String = "chapter") =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .take(80)
        .ifBlank { fallback }
