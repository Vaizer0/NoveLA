package my.noveldokusha.features.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * YouTube-like thin seekable progress bar for TTS playback.
 *
 * @param progress fraction 0f..1f of elapsed / total.
 * @param elapsedSeconds live elapsed seconds.
 * @param totalSeconds fixed total duration seconds.
 * @param enabled whether seeking is allowed.
 * @param onSeek fraction (0f..1f) of the position the user dragged/tapped to.
 */
@Composable
internal fun TtsProgressSeekBar(
    progress: Float,
    elapsedSeconds: Int,
    totalSeconds: Int,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
) {
    val clamped = progress.coerceIn(0f, 1f)
    var showRemaining by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = statusText ?: formatDuration(elapsedSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val w = size.width.toFloat()
                                    if (w > 0f) onSeek((offset.x / w).coerceIn(0f, 1f))
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val w = size.width.toFloat()
                                    if (w > 0f) onSeek((change.position.x / w).coerceIn(0f, 1f))
                                }
                            }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth: Dp = maxWidth
            val thumbOffset: Dp = trackWidth * clamped

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
            )
            Box(
                modifier = Modifier
                    .width(trackWidth * clamped)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            if (enabled) {
                Box(
                    modifier = Modifier
                        .offset(x = thumbOffset)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Text(
            text = statusText ?: if (showRemaining && totalSeconds > 0) {
                "-${formatDuration((totalSeconds - elapsedSeconds).coerceAtLeast(0))}"
            } else {
                formatDuration(totalSeconds)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { showRemaining = !showRemaining }
                        }
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m}:${s.toString().padStart(2, '0')}"
    }
}
