# Floating TTS Menu Cleanup

## Overview

Three changes to the floating TTS mini player:
1. Paragraph text always visible (remove toggle)
2. Opacity/width sliders → YouTube-style thin lines
3. Remove all TTS duration/timer bloat, replace with chapter % progress

## Files Modified

| File | Change |
|------|--------|
| `FloatingTtsService.kt` | Remove `showText` state |
| `FloatingTtsOverlay.kt` | Remove `showTextToggle`/`onShowTextToggle` params |
| `TtsMiniPlayer.kt` | Para always on, thin sliders, remove progress bar/timer, add chapter % |
| `ReaderTextToSpeech.kt` | Remove `estimatedTotalSeconds`, `estimatedRemainingSeconds`, `estimatedWpm`, `chapterWordCount`, `remainingWordCount`, `chapterCharacterCount`, `remainingCharacterCount`, CPS calibration |
| `TextToSpeechSettingData` (in ReaderTextToSpeech.kt) | Remove timer-related fields, add chapter progress |

## Change 1: Paragraph Always On

- Remove `showText` mutable state from `FloatingTtsService`
- Remove `showTextToggle`/`onShowTextToggle` from `FloatingTtsOverlayContent` and `TtsMiniPlayer`/`FloatingTtsMiniPlayer`
- Remove the text toggle icon button (trailingAction) from `MiniPlayerControls`
- `hasParagraphText` = `displayText.isNotBlank() || isBothMode` (no `showParagraphText` condition)

## Change 2: Thin YouTube-Style Sliders

Replace both `Slider` composables with a custom `ThinSlider` composable:
- Track: 2dp height rounded rect
- Active track color: `MaterialTheme.colorScheme.primary`
- Inactive track color: `onSurface.copy(alpha = 0.2f)`
- Thumb: 8dp circle
- Uses `pointerInput` + `detectDragGestures` + `Canvas` for drawing
- Same value range and behavior as current sliders

## Change 3: Remove TTS Duration Bloat + Chapter %

### Remove from `TextToSpeechSettingData`:
- `chapterWordCount`
- `remainingWordCount`
- `estimatedWpm`
- `estimatedTotalSeconds`
- `estimatedRemainingSeconds`

### Remove from `ReaderTextToSpeech`:
- `chapterCharacterCount`, `remainingCharacterCount` derived states
- `baseCharactersPerSecond` and its calibration logic (init block measurement)
- CPS measurement code using paragraph start times

### Add to `TextToSpeechSettingData`:
- `chapterProgressPercent: State<Int>` — derived from `currentItemPosition / totalItemsInChapter * 100`

### In `MiniPlayerControls`:
- Remove the progress bar Box with `fillMaxWidth(animatedProgress)`
- Remove the time display Surface (AccessTime icon + duration text)
- Replace with a simple Text showing chapter % (e.g. "45%")
- Also remove `animatedProgress` and `remaining` params from `MiniPlayerControls`
- Remove `formatDuration()` function
