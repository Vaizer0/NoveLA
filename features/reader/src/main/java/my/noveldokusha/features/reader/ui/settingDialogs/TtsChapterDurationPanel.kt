package my.noveldokusha.features.reader.ui.settingDialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.utils.formatDuration

@Composable
internal fun TtsChapterDurationPanel(
    totalMs: Long?,
    estimatedSeconds: Int = 0,
    currentMs: Long,
    remainingMs: Long,
    loading: Boolean,
    provisional: Boolean,
) {
    var showTotal by rememberSaveable { mutableStateOf(false) }

    val totalSeconds = (totalMs ?: 0L).coerceAtLeast(0L).div(1000L).toInt()
    val currentSeconds = currentMs.coerceAtLeast(0L).div(1000L).toInt()
    val remainingSeconds = remainingMs.coerceAtLeast(0L).div(1000L).toInt()
    val fallbackSeconds = estimatedSeconds.coerceAtLeast(0)
    val hasMeasuredDuration = totalMs != null && totalSeconds > 0
    val progress = if (hasMeasuredDuration) {
        (currentMs.toFloat() / totalMs!!.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val progressColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = formatDuration(currentSeconds),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.width(54.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val trackHeight = 4.dp.toPx()
                val radius = trackHeight / 2f
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(size.width, trackHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
                if (progress > 0f) {
                    drawRoundRect(
                        color = progressColor,
                        topLeft = Offset(0f, centerY - radius),
                        size = Size(size.width * progress, trackHeight),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    drawCircle(
                        color = progressColor,
                        radius = 6.dp.toPx(),
                        center = Offset(size.width * progress, centerY),
                    )
                }
            }
        }

        val rightText = when {
            hasMeasuredDuration && showTotal -> formatDuration(totalSeconds)
            hasMeasuredDuration -> "-${formatDuration(remainingSeconds)}"
            fallbackSeconds > 0 -> "~${formatDuration(fallbackSeconds)}"
            loading -> "…"
            else -> "—:—"
        }
        Text(
            text = if (provisional && hasMeasuredDuration) "~$rightText" else rightText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier
                .width(54.dp)
                .clickable(enabled = hasMeasuredDuration) { showTotal = !showTotal },
        )
    }
}
