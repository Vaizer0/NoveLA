# Task 1 Implementation Plan: Create Reusable TTS Progress Seek Bar Component

## Overview
Create a new `TtsProgressSeekBar.kt` composable that provides a thin seek bar with elapsed/total times, supporting tap/drag seeking and remaining time toggle.

## Files to Create
- `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressSeekBar.kt`

## Implementation Steps

### Step 1: Create TtsProgressSeekBar.kt

**Location:** `features/reader/src/main/java/my/noveldokusha/features/reader/ui/`

**Component signature:**
```kotlin
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
)
```

**Key features:**
1. **Layout:** Row with elapsed text, seek bar, total/remaining text
2. **Seek bar:** Thin bar (3dp default) with round thumb (12dp default)
3. **Colors:** Background `onSurface.copy(alpha = 0.2f)`, filled + thumb `primary`
4. **Gestures:** Tap to seek, drag to seek with visual feedback
5. **Time display:** Elapsed on left, total/remaining on right
6. **Toggle:** Tap right label to toggle between total and remaining (negative format)
7. **Calculating state:** Show "..." instead of total time when `isCalculating = true`

**Private helper function:**
```kotlin
private fun formatDuration(seconds: Int): String
```
- Handles negative values for remaining time display (e.g., `-4:07`)
- Formats as `H:MM:SS` for hours, `M:SS` for minutes

### Step 2: Verify compilation
- Ensure the file compiles without errors
- Check that imports are correct and complete

### Step 3: Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressSeekBar.kt
git commit -m "feat(tts): add reusable TtsProgressSeekBar component"
```

## Design Notes

1. **No modifications to existing files** - This task only creates a new file
2. **Follows existing patterns** - Uses Material 3 components consistent with TtsMiniPlayer.kt
3. **Self-contained** - Private `formatDuration` function (different from existing one in TtsMiniPlayer.kt which only handles positive values)
4. **Reusable** - Designed to be used in both main reader and floating mini TTS player

## Risks & Mitigations
- **Risk:** Duplicate `formatDuration` function
- **Mitigation:** The new function handles negative values; existing one doesn't. Can be unified later if needed.

## Verification
- File should compile without errors
- Should follow existing code style in the project
- Should use Material 3 components consistently with other UI files
