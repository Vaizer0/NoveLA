# Task 9: Add Floating TTS + Show Outside App Toggles Below Button Row

## Context
This is Task 9 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

## Goal
Add Floating TTS and Show Outside App toggles in a row below the 4-button row (Start Here, Focus, Voices, Saved Voices).

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt`

## Layout
```
Row 1: [Start Here] [Focus] [Voices] [Saved Voices]
Row 2: [Floating TTS] [Show Outside App]    ← NEW: toggles below
Row 3: [⏮ Ch] [⏮ Para] [▶/⏸] [⏭ Para] [⏭ Ch]
Row 4: [1:23] ────●──────── [5:30]
```

## Requirements
1. Find the existing Floating TTS and Show Outside App toggle switches
2. Move them into a new Row below the 4-button row
3. Keep them compact and tidy
4. Use existing toggle/switch components from the codebase

## Implementation Steps

### Step 1: Locate existing Floating TTS and Show Outside App toggles

Search for these in `VoiceReaderSettingDialog.kt`:
- `R.string.tts_floating` 
- `R.string.tts_floating_show_outside_app`

### Step 2: Create a new Row below the 4-button row

After the existing 4-button row, add:

```kotlin
// Floating TTS + Show Outside App toggles
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically
) {
    // Floating TTS toggle (existing code, moved here)
    // Show Outside App toggle (existing code, moved here)
}
```

### Step 3: Move existing toggle code into the new Row

Move the existing toggle/switch code for Floating TTS and Show Outside App into this new Row.

## Verification
- File should compile without errors
- Toggles should appear below the 4-button row
- Existing functionality preserved

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt
git commit -m "feat(tts): add floating TTS and show outside app toggles below button row"
```
