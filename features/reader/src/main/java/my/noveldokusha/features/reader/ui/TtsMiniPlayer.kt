package my.noveldokusha.features.reader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.composableActions.debouncedAction
import my.noveldokusha.features.reader.features.TextToSpeechSettingData

@Composable
internal fun TtsMiniPlayer(
    state: TextToSpeechSettingData,
    onClose: () -> Unit,
    chapterCurrentNumber: Int = 0,
    chaptersCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val total = state.estimatedTotalSeconds.value
    val remaining = state.estimatedRemainingSeconds.value

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(start = 2.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
        ) {
            IconButton(
                onClick = debouncedAction(waitMillis = 100, action = onClose),
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                )
            }
            IconButton(
                onClick = debouncedAction(waitMillis = 100) { state.playPreviousItem() },
                enabled = state.isThereActiveItem.value,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.NavigateBefore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                )
            }
            IconButton(
                onClick = { state.setPlaying(!state.isPlaying.value) },
                modifier = Modifier.size(40.dp)
            ) {
                AnimatedContent(
                    targetState = state.isPlaying.value,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    label = ""
                ) { target ->
                    when (target) {
                        true -> Icon(
                            Icons.Rounded.Pause,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        false -> Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            IconButton(
                onClick = debouncedAction(waitMillis = 100) { state.playNextItem() },
                enabled = state.isThereActiveItem.value,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.NavigateNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                )
            }
            if (chaptersCount > 0) {
                Text(
                    text = "Ch. $chapterCurrentNumber/$chaptersCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            Text(
                text = "${formatDurationCompact(remaining)} / ${formatDurationCompact(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDurationCompact(seconds: Int): String {
    if (seconds <= 0) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}h ")
        if (m > 0 || h > 0) append("${m}m ")
        if (s > 0 && h == 0) append("${s}s")
    }.trimEnd()
}
