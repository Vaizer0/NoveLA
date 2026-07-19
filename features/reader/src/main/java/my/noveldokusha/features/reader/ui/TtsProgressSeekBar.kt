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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toDp

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
) {
    val density = LocalDensity.current
    val barHeight = 3.dp
    val thumbSize = 12.dp
    var showRemaining by remember { mutableStateOf(false) }

    val clamped = progress.coerceIn(0f, 1f)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDuration(elapsedSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(thumbSize)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        if (w > 0f) onSeek((offset.x / w).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        val w = size.width.toFloat()
                        if (w > 0f) onSeek((change.position.x / w).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth = maxWidth
            val thumbPx = with(density) { thumbSize.toPx() }
            val trackPx = with(density) { trackWidth.toPx() }
            val thumbOffsetPx = (trackPx - thumbPx).coerceAtLeast(0f) * clamped

            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
            )
            // Progress fill
            Box(
                modifier = Modifier
                    .width(with(density) { (trackPx * clamped).toDp() })
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Thumb dot
            if (enabled) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(thumbOffsetPx.toInt(), 0) }
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Text(
            text = if (showRemaining && totalSeconds > 0) {
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
                    } else Modifier
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
