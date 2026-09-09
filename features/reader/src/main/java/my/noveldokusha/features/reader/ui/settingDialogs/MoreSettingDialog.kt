package my.noveldokusha.features.reader.ui.settingDialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.FormatPaint
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.colorAccent
import my.noveldokusha.reader.R

private val ttsHighlightColors = listOf(
    "FFFF6D00",  // Deep Orange
    "FFFF1744",  // Red Accent
    "FFFF4081",  // Pink Accent
    "FFE040FB",  // Purple Accent
    "FF7C4DFF",  // Deep Purple Accent
    "FF536DFE",  // Indigo Accent
    "FF448AFF",  // Blue Accent
    "FF40C4FF",  // Light Blue Accent
    "FF18FFFF",  // Cyan Accent
    "FF64FFDA",  // Teal Accent
    "FF69F0AE",  // Green Accent
    "FFFFD740",  // Amber Accent
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoreSettingDialog(
    ttsDurationEnabled: Boolean,
    onTtsDurationEnabledChange: (Boolean) -> Unit,
    ttsHighlightEnabled: Boolean,
    onTtsHighlightEnabledChange: (Boolean) -> Unit,
    ttsHighlightColor: String,
    onTtsHighlightColorChange: (String) -> Unit,
    manualHighlightEnabled: Boolean = false,
    onManualHighlightEnabledChange: (Boolean) -> Unit = {},
) {
    LaunchedEffect(ttsDurationEnabled) {
        if (ttsDurationEnabled) {
            // The TTS engine/voice may finish initialization after the toggle is enabled.
            // Re-invoke the existing setter once so a previously rejected measurement is retried.
            delay(1200)
            if (ttsDurationEnabled) onTtsDurationEnabledChange(true)
        }
    }

    ElevatedCard(
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp)
    ) {
        // TTS chapter duration — intentionally lives in More, immediately above Manual highlight.
        SlimListItem(
            modifier = Modifier
                .clickable { onTtsDurationEnabledChange(!ttsDurationEnabled) },
            headlineContent = {
                Text(text = "TTS chapter duration")
            },
            leadingContent = {
                Icon(
                    Icons.Rounded.AccessTime,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Switch(
                    checked = ttsDurationEnabled,
                    onCheckedChange = onTtsDurationEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorAccent(),
                        checkedTrackColor = colorAccent().copy(alpha = 0.4f),
                    )
                )
            }
        )

        // Manual highlight
        SlimListItem(
            modifier = Modifier
                .clickable { onManualHighlightEnabledChange(!manualHighlightEnabled) },
            headlineContent = {
                Text(text = stringResource(id = R.string.manual_highlight))
            },
            leadingContent = {
                Icon(
                    Icons.Outlined.Highlight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Switch(
                    checked = manualHighlightEnabled,
                    onCheckedChange = onManualHighlightEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorAccent(),
                        checkedTrackColor = colorAccent().copy(alpha = 0.4f),
                    )
                )
            }
        )
        // TTS Highlight
        SlimListItem(
            modifier = Modifier
                .clickable { onTtsHighlightEnabledChange(!ttsHighlightEnabled) },
            headlineContent = {
                Text(text = stringResource(id = R.string.tts_highlight))
            },
            leadingContent = {
                Icon(
                    Icons.Outlined.FormatPaint,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Switch(
                    checked = ttsHighlightEnabled,
                    onCheckedChange = onTtsHighlightEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorAccent(),
                        checkedTrackColor = colorAccent().copy(alpha = 0.4f),
                    )
                )
            }
        )
        if (ttsHighlightEnabled) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ttsHighlightColors.forEach { hexColor ->
                    val isSelected = hexColor == ttsHighlightColor
                    val color = try {
                        Color(android.graphics.Color.parseColor("#$hexColor"))
                    } catch (_: Exception) {
                        Color(android.graphics.Color.parseColor("#FFFF6D00"))
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { onTtsHighlightColorChange(hexColor) }
                    )
                }
            }
        }
    }
}
