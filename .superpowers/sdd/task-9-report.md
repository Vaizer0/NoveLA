# Task 9 Report: Floating TTS + Show Outside App Toggles

## What was implemented

Moved the Floating TTS and Show Outside App toggle switches from their original Surface/Column container into a compact horizontal Row placed directly below the 4-button chip row (Start Here, Focus, Voices, Saved Voices).

**New layout:**
```
Row 1: [Start Here] [Focus] [Voices] [Saved Voices]
Row 2: [Floating TTS] [Show Outside App]    ← NEW
Row 3: [⏮ Ch] [⏮ Para] [▶/⏸] [⏭ Para] [⏭ Ch]
Row 4: [1:23] ────●──────── [5:30]
```

## Files changed

- `features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt`

**Changes:**
1. Replaced the `Surface > Column > Row + HorizontalDivider + Row` toggle block (lines 255-299) with a compact `Row` containing two inline toggle groups
2. Each toggle is now `Row { Text + Switch }` with `bodySmall` typography for compactness
3. Removed unused imports: `BorderStroke`, `RoundedCornerShape`, `Modifier.height`, `Modifier.width`

## Self-review findings

- All functionality preserved: Floating TTS toggle still controls `floatingTtsState.isEnabled`, Show Outside App toggle still controls `floatingTtsState.showOutsideApp` with proper `enabled` dependency
- The `Surface` import remains in use by `VoiceSelectorDialog`'s sticky header
- No build tools available in this environment, so compilation was verified by code review only
- No bracket/brace mismatches detected
