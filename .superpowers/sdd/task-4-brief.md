# Task 4: Integrate Seek Bar into TtsMiniPlayer

## Context
This is Task 4 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

Tasks 1-3 created the seek bar component and timing engine. This task integrates the seek bar into the floating mini TTS player.

## Goal
Replace the existing thick progress bar in `TtsMiniPlayer.kt` with the new thin `TtsProgressSeekBar`.

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt`

## Requirements
1. Replace the existing progress bar in `MiniPlayerControls` with `TtsProgressSeekBar`
2. The seek bar should be below the playback controls (Prev/Play/Next)
3. Use compact sizing: `barHeight = 2.dp`, `thumbSize = 10.dp`
4. Connect to timing states from `TextToSpeechSettingData`
5. Keep existing functionality (Close, Chapter badge, Start Here, etc.)

## Implementation Steps

### Step 1: Add import for TtsProgressSeekBar

Add to the imports at the top of the file:
```kotlin
import my.noveldokusha.features.reader.ui.TtsProgressSeekBar
```

### Step 2: Update FloatingTtsMiniPlayer to include seek bar

Find the `FloatingTtsMiniPlayer` composable. After the `MiniPlayerControls` call, add the seek bar:

```kotlin
// After MiniPlayerControls:
if (!menuHidden) {
    TtsProgressSeekBar(
        elapsed = state.chapterElapsedSeconds.value,
        total = state.chapterTotalSeconds.value,
        isCalculating = state.isCalculatingDuration.value,
        showRemaining = state.showRemainingTime.value,
        onSeek = { state.seekToPosition(it) },
        onToggleRemaining = { state.showRemainingTime.value = !state.showRemainingTime.value },
        barHeight = 2.dp,
        thumbSize = 10.dp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
```

### Step 3: Remove old progress bar from MiniPlayerControls

In `MiniPlayerControls`, remove the old progress bar `Box` that uses `animatedProgress`. The old code looks like:

```kotlin
Box(
    modifier = Modifier
        .weight(1f)
        .padding(horizontal = 4.dp)
        .height(progressHeight)
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(animatedProgress)
            .background(MaterialTheme.colorScheme.primary)
    )
}
```

Remove this entire block. Also remove the `animatedProgress` parameter from `MiniPlayerControls` since it's no longer needed.

### Step 4: Update MiniPlayerControls signature

Remove the `animatedProgress` parameter from `MiniPlayerControls`:

```kotlin
@Composable
private fun MiniPlayerControls(
    state: TextToSpeechSettingData,
    onClose: () -> Unit,
    onStartHere: () -> Unit,
    chapterCurrentNumber: Int,
    chaptersCount: Int,
    remaining: Int,
    // ... other parameters ...
    // REMOVE: animatedProgress: Float,
    extraAction: @Composable () -> Unit = {},
    trailingAction: @Composable () -> Unit = {},
)
```

Also remove the `animatedProgress` calculation at the top of `FloatingTtsMiniPlayer`:
```kotlin
// REMOVE these lines:
val total = state.estimatedTotalSeconds.value
val remaining = state.estimatedRemainingSeconds.value
val progress = if (total > 0) (total - remaining).toFloat() / total else 0f
val animatedProgress by animateFloatAsState(
    targetValue = progress.coerceIn(0f, 1f),
    animationSpec = tween(durationMillis = 300),
    label = ""
)
```

## Verification
- File should compile without errors
- Mini player should show thin seek bar below playback controls
- Seek bar should respond to drag/tap
- Remaining time toggle should work

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt
git commit -m "feat(tts): integrate seek bar into mini player"
```
