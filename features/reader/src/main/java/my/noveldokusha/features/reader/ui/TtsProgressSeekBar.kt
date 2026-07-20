package my.noveldokusha.features.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TtsProgressSeekBar(
    elapsed: Int,
    total: Int,
    isCalculating: Boolean,
    showRemaining: Boolean,
    onSeek: (Int) -> Unit,
    onToggleRemaining: () -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 3.dp,
    thumbSize: Dp = 12.dp,
) {
    val progress = if (total > 0) elapsed.toFloat() / total else 0f
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val displayProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        // Elapsed time
        Text(
            text = formatDuration(elapsed),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Seek bar
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(barHeight + thumbSize)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val barWidth = size.width.toFloat()
                            val tapPosition = (offset.x / barWidth).coerceIn(0f, 1f)
                            val seekSeconds = (tapPosition * total).toInt()
                            onSeek(seekSeconds)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val barWidth = size.width.toFloat()
                            dragProgress = (offset.x / barWidth).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val barWidth = size.width.toFloat()
                            dragProgress = (change.position.x / barWidth).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            val seekSeconds = (dragProgress * total).toInt()
                            onSeek(seekSeconds)
                        }
                    )
                }
        ) {
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
            )
            // Filled track
            Box(
                modifier = Modifier
                    .fillMaxWidth(displayProgress)
                    .height(barHeight)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Thumb
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .align(Alignment.CenterStart)
                    .offset(x = (displayProgress * 100).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        // Total/Remaining time
        Text(
            text = if (isCalculating) "..." else {
                val displaySeconds = if (showRemaining) -(total - elapsed) else total
                formatDuration(displaySeconds)
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { onToggleRemaining() }
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val absSeconds = kotlin.math.abs(seconds)
    val prefix = if (seconds < 0) "-" else ""
    val h = absSeconds / 3600
    val m = (absSeconds % 3600) / 60
    val s = absSeconds % 60
    return if (h > 0) {
        "${prefix}${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${prefix}${m}:${s.toString().padStart(2, '0')}"
    }
}
