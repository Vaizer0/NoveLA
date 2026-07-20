# Task 5: Update Main Reader Layout with 3-Row Design

## Context
This is Task 5 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

Tasks 1-4 created the seek bar component and integrated it into the mini player. This task updates the main reader layout.

## Goal
Update the main reader TTS controls to use a 3-row layout:
- Row 1: Start Here | Focus | Voices | Saved Voices (4 buttons in single row)
- Row 2: Prev Ch | Prev Para | Play/Pause | Next Para | Next Ch (playback controls)
- Row 3: Seek bar with elapsed/total times

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt`

## Requirements
1. Find the existing TTS controls section in the reader
2. Reorganize into 3 rows as described above
3. The 4 buttons (Start Here, Focus, Voices, Saved Voices) should be in a single horizontal row
4. Add `TtsProgressSeekBar` below the playback controls
5. Keep existing functionality unchanged

## Important Notes

- The file is large. Work carefully and make targeted edits.
- The existing TTS controls are likely in a `Column` or `Row` near the bottom of the reader UI.
- Look for sections that reference `textToSpeechSettingData` or TTS-related controls.
- The `TtsProgressSeekBar` component is in the same package.

## Implementation Steps

### Step 1: Locate the existing TTS controls

Search for the TTS controls section in `ReaderScreen.kt`. Look for:
- References to `textToSpeechSettingData`
- Buttons for Start Here, Focus, Voices, Saved Voices
- Playback controls (Prev/Play/Next)

### Step 2: Add import for TtsProgressSeekBar

Add to the imports:
```kotlin
import my.noveldokusha.features.reader.ui.TtsProgressSeekBar
```

### Step 3: Reorganize into 3-row layout

Replace the existing TTS controls with:

```kotlin
// Row 1: 4 buttons in single row
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly
) {
    // Start Here button (existing code)
    // Focus button (existing code)
    // Voices button (existing code)
    // Saved Voices button (existing code)
}

// Row 2: Playback controls
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
) {
    // Prev Chapter (existing code)
    // Prev Paragraph (existing code)
    // Play/Pause (existing code)
    // Next Paragraph (existing code)
    // Next Chapter (existing code)
}

// Row 3: Seek bar
TtsProgressSeekBar(
    elapsed = textToSpeechSettingData.chapterElapsedSeconds.value,
    total = textToSpeechSettingData.chapterTotalSeconds.value,
    isCalculating = textToSpeechSettingData.isCalculatingDuration.value,
    showRemaining = textToSpeechSettingData.showRemainingTime.value,
    onSeek = { textToSpeechSettingData.seekToPosition(it) },
    onToggleRemaining = { textToSpeechSettingData.showRemainingTime.value = !textToSpeechSettingData.showRemainingTime.value },
    modifier = Modifier.padding(horizontal = 16.dp)
)
```

## Verification
- File should compile without errors
- Main reader should show 3-row layout
- 4 buttons should be in a single row
- Seek bar should work correctly

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt
git commit -m "feat(tts): update main reader layout with 3-row design"
```
