package my.noveldokusha.features.chapterslist

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.InternalTheme
import my.noveldokusha.coreui.theme.PreviewThemes
import my.noveldokusha.chapterslist.R
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.strings.R as StringsR

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
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
    onVideoOriginal: () -> Unit = {},
    onVideoTranslated: () -> Unit = {},
    translatedAudioAvailable: Boolean = true,
) {
    val chapter = chapterWithContext.chapter
    val sizeLabel = chapterSize?.sizeBytes?.let { formatBytes(it) }

    val targetContainerColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        highlighted -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(durationMillis = 200),
        label = "chapterItemBackground"
    )

    val stableOnClick = remember(onClick) { onClick }
    val stableOnLongClick = remember(onLongClick) { onLongClick }
    val stableOnDownload = remember(onDownload) { onDownload }
    val stableOnAudioOriginal = remember(onAudioOriginal) { onAudioOriginal }
    val stableOnAudioTranslated = remember(onAudioTranslated) { onAudioTranslated }
    val stableOnVideoOriginal = remember(onVideoOriginal) { onVideoOriginal }
    val stableOnVideoTranslated = remember(onVideoTranslated) { onVideoTranslated }

    val badge: @Composable (() -> Unit)? = remember(chapterWithContext.lastReadChapter, chapter.read) {
        when {
            chapterWithContext.lastReadChapter -> {
                {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = stringResource(id = R.string.last_read),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            chapter.read -> {
                {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = stringResource(id = R.string.read),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            else -> null
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.5.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .combinedClickable(onClick = stableOnClick, onLongClick = stableOnLongClick)
        ) {
            SlimListItem(
                headlineContent = {
                    Text(
                        text = translatedTitle ?: chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chapter.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = if (badge != null || sizeLabel != null) {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (badge != null) badge()
                            if (sizeLabel != null) {
                                Text(
                                    text = sizeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else null,
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (!isLocalSource) {
                            AnimatedTransition(
                                targetState = chapterWithContext.downloaded,
                                transitionSpec = { fadeIn() togetherWith fadeOut() }
                            ) { downloaded ->
                                IconButton(onClick = stableOnDownload) {
                                    Icon(
                                        if (downloaded) Icons.Filled.CloudDownload else Icons.Outlined.CloudDownload,
                                        null
                                    )
                                }
                            }
                        }
                        ChapterAudioButton(
                            source = TtsAudioSource.ORIGINAL,
                            audioJob = audioOriginalJob,
                            audioFileExists = audioOriginalFileExists,
                            onAudio = stableOnAudioOriginal,
                        )
                        ChapterAudioButton(
                            source = TtsAudioSource.TRANSLATED,
                            audioJob = audioTranslatedJob,
                            audioFileExists = audioTranslatedFileExists,
                            enabled = translatedAudioAvailable || audioTranslatedJob != null,
                            onAudio = stableOnAudioTranslated,
                        )
                        ChapterVideoButton(
                            source = TtsAudioSource.ORIGINAL,
                            onVideo = stableOnVideoOriginal,
                        )
                        ChapterVideoButton(
                            source = TtsAudioSource.TRANSLATED,
                            enabled = translatedAudioAvailable,
                            onVideo = stableOnVideoTranslated,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ChapterVideoButton(
    source: TtsAudioSource,
    enabled: Boolean = true,
    onVideo: () -> Unit,
) {
    val sourceLabel = stringResource(
        when (source) {
            TtsAudioSource.ORIGINAL -> StringsR.string.tts_audio_source_original
            TtsAudioSource.TRANSLATED -> StringsR.string.tts_audio_source_translated
            TtsAudioSource.ASK_EVERY_TIME -> StringsR.string.tts_audio_chapter_action
        }
    )
    IconButton(onClick = onVideo, enabled = enabled) {
        Icon(
            imageVector = if (enabled) Icons.Outlined.PlayArrow else Icons.Outlined.PlayArrow,
            contentDescription = "$sourceLabel video",
            tint = if (enabled) LocalContentColor.current
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(23.dp),
        )
    }
}

@Composable
private fun ChapterAudioButton(
    source: TtsAudioSource,
    audioJob: TtsAudioJobState?,
    audioFileExists: Boolean,
    enabled: Boolean = true,
    onAudio: () -> Unit
) {
    val status = audioJob?.status
    val running = status == TtsAudioJobStatus.QUEUED || status == TtsAudioJobStatus.RUNNING
    val clickable = enabled || audioJob != null
    val contentDescription = stringResource(
        when (status) {
            TtsAudioJobStatus.QUEUED -> StringsR.string.tts_audio_status_queued
            TtsAudioJobStatus.RUNNING -> StringsR.string.tts_audio_status_running
            TtsAudioJobStatus.SUCCESS -> StringsR.string.tts_audio_downloaded
            TtsAudioJobStatus.FAILED -> StringsR.string.tts_audio_download_failed
            TtsAudioJobStatus.CANCELLED -> StringsR.string.tts_audio_chapter_action
            null -> StringsR.string.tts_audio_chapter_action
        }
    ).let { statusDesc ->
        val sourceLabel = stringResource(
            when (source) {
                TtsAudioSource.ORIGINAL -> StringsR.string.tts_audio_source_original
                TtsAudioSource.TRANSLATED -> StringsR.string.tts_audio_source_translated
                TtsAudioSource.ASK_EVERY_TIME -> StringsR.string.tts_audio_chapter_action
            }
        )
        if (sourceLabel.isNotBlank()) "$sourceLabel: $statusDesc" else statusDesc
    }

    when {
        running -> {
            val percent = (audioJob!!.progress).coerceIn(0, 100)
            val progressDesc = stringResource(StringsR.string.tts_audio_progress_percent, percent)
            IconButton(onClick = onAudio) {
                Box {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        progress = { percent / 100f },
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "$percent",
                        modifier = Modifier.align(Alignment.Center),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Icon(
                        sourceFilledIcon(source),
                        contentDescription = "$contentDescription ($progressDesc)",
                        modifier = Modifier.align(Alignment.BottomEnd).size(12.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
        status == TtsAudioJobStatus.SUCCESS && audioFileExists -> {
            IconButton(onClick = onAudio) {
                Box {
                    Icon(sourceFilledIcon(source), contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        status == TtsAudioJobStatus.FAILED -> {
            IconButton(onClick = onAudio) {
                Box {
                    Icon(sourceFilledIcon(source), contentDescription = contentDescription, tint = MaterialTheme.colorScheme.error)
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).size(12.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        else -> {
            IconButton(onClick = { if (clickable) onAudio() }, enabled = clickable) {
                Icon(
                    sourceIdleIcon(source),
                    contentDescription = contentDescription,
                    tint = if (clickable) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
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

@PreviewThemes
@Composable
private fun PreviewView(
    @PreviewParameter(PreviewProvider::class) previewProviderState: PreviewProviderState
) {
    InternalTheme {
        ChaptersScreenChapterItem(
            chapterWithContext = previewProviderState.chapterWithContext,
            selected = previewProviderState.selected,
            isLocalSource = false,
            onLongClick = {},
            onClick = {},
            onDownload = {},
            onAudioOriginal = {},
            onAudioTranslated = {},
            onVideoOriginal = {},
            onVideoTranslated = {},
        )
    }
}

private data class PreviewProviderState(
    val chapterWithContext: ChapterWithContext,
    val selected: Boolean
)

private class PreviewProvider : PreviewParameterProvider<PreviewProviderState> {
    override val values = sequenceOf(
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Title of the chapter",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 0,
                    read = false
                ),
                downloaded = false,
                lastReadChapter = false
            ),
            selected = false
        ),
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Title of the chapter, Title of the chapter, Title of the chapter, Title of the chapter, Title of the chapter,Title of the chapter ,Title of the chapter",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 0,
                    read = true
                ),
                downloaded = true,
                lastReadChapter = false
            ),
            selected = false
        ),
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Title of the chapter",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 0,
                    read = false
                ),
                downloaded = true,
                lastReadChapter = true
            ),
            selected = true
        )
    )
}
