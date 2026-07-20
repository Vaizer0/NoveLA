# Task 1: Create Reusable TTS Progress Seek Bar Component

## Context
This is Task 1 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

## Goal
Create a reusable thin seek bar component with elapsed/total times that will be used in both the main reader and floating mini TTS player.

## Files to Create
- `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressSeekBar.kt`

## Requirements
- Thin bar (2-3dp height) with small round dot/thumb (12dp)
- Elapsed time on left, total time on right (same row)
- Tap right time label toggles between total and remaining (negative format like -4:07)
- Drag/tap bar seeks to position
- When `isCalculating` is true, show "..." instead of total time
- Colors: bar background `onSurface.copy(alpha = 0.2f)`, filled bar and dot `MaterialTheme.colorScheme.primary`

## Interfaces
- **Input:** `elapsed: Int`, `total: Int`, `isCalculating: Boolean`, `showRemaining: Boolean`, `onSeek: (Int) -> Unit`, `onToggleRemaining: () -> Unit`
- **Output:** Composable UI element

## Implementation Steps

### Step 1: Create the TtsProgressSeekBar composable

```kotlin
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
                    .offset(x = (displayProgress * (size.width - thumbSize.toPx())).toDp())
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
```

### Step 2: Commit

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressSeekBar.kt
git commit -m "feat(tts): add reusable TtsProgressSeekBar component"
```

## Verification
- File should compile without errors
- Should follow existing code style in the project
- Should use Material 3 components consistently with other UI files
