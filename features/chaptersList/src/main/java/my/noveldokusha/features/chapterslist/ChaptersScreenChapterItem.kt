package my.noveldokusha.features.chapterslist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.chapterslist.R
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.feature.local_database.ChapterWithContext
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChaptersScreenChapterItem(
    chapterWithContext: ChapterWithContext,
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
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 0.5.dp, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            SlimListItem(
                headlineContent = { Text(translatedTitle ?: chapter.title, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = chapterSize?.sizeBytes?.let { { Text(formatBytes(it), style = MaterialTheme.typography.labelSmall) } },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (!isLocalSource) {
                            AnimatedTransition(targetState = chapterWithContext.downloaded) { downloaded ->
                                IconButton(onClick = onDownload) { Icon(if (downloaded) Icons.Filled.CloudDownload else Icons.Outlined.CloudDownload, null) }
                            }
                        }
                        ChapterAudioButton(TtsAudioSource.ORIGINAL, audioOriginalJob, audioOriginalFileExists, true, onAudioOriginal)
                        ChapterAudioButton(TtsAudioSource.TRANSLATED, audioTranslatedJob, audioTranslatedFileExists, translatedAudioAvailable || audioTranslatedJob != null, onAudioTranslated)
                    }
                }
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
    onAudio: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status = audioJob?.status
    val phase = audioJob?.phase?.uppercase()
    val running = audioJob != null && audioJob.isActive
    val audioReady = status == TtsAudioJobStatus.SUCCESS && phase == "AUDIO" && audioJob.audioUri.isNotBlank() &&
        audioFileExists && audioJob.timelineUri.isNotBlank() && documentExists(context, audioJob.timelineUri)
    val videoReady = status == TtsAudioJobStatus.SUCCESS && phase == "VIDEO" && audioFileExists && audioJob.documentUri.isNotBlank()
    val clickable = enabled || audioJob != null

    when {
        running -> {
            val p = audioJob!!.progress.coerceIn(0, 100)
            val c = if (phase == "VIDEO") Color(0xFFFF9800) else MaterialTheme.colorScheme.tertiary
            IconButton(onClick = {}) {
                Box {
                    CircularProgressIndicator(Modifier.size(28.dp), progress = { p / 100f }, strokeWidth = 2.dp, color = c)
                    Text("$p", Modifier.align(Alignment.Center), fontSize = 9.sp, color = c)
                    Icon(sourceFilledIcon(source), null, Modifier.align(Alignment.BottomEnd).size(12.dp), tint = c)
                }
            }
        }
        videoReady -> {
            IconButton(onClick = { openDocument(context, audioJob!!.documentUri, "video/mp4") }) {
                Box {
                    Icon(sourceFilledIcon(source), null, tint = Color(0xFFFF9800))
                    Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.BottomEnd).size(12.dp), tint = Color(0xFFFF9800))
                }
            }
        }
        audioReady -> {
            val blue = Color(0xFF2196F3)
            IconButton(onClick = { my.noveldokusha.tooling.application_workers.TtsVideoExportQueue.enqueueFromJob(context, audioJob!!) }) {
                Box {
                    Icon(sourceFilledIcon(source), null, tint = blue)
                    Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.BottomEnd).size(12.dp), tint = blue)
                }
            }
        }
        status == TtsAudioJobStatus.FAILED -> {
            IconButton(onClick = onAudio) {
                Box {
                    Icon(sourceFilledIcon(source), null, tint = MaterialTheme.colorScheme.error)
                    Icon(Icons.Filled.Refresh, null, Modifier.align(Alignment.BottomEnd).size(12.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        else -> {
            IconButton(onClick = onAudio, enabled = clickable) {
                Icon(sourceIdleIcon(source), null, tint = if (clickable) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
            }
        }
    }
}

private fun documentExists(context: Context, uriString: String): Boolean = runCatching {
    context.contentResolver.query(Uri.parse(uriString), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
}.getOrDefault(false)

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
