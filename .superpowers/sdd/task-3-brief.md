# Task 3: Update TextToSpeechSettingData to Expose Timing States

## Context
This is Task 3 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

Task 2 added the timing engine to `ReaderTextToSpeech.kt`. This task exposes those timing states through `TextToSpeechSettingData`.

## Goal
Add timing fields to `TextToSpeechSettingData` so they can be consumed by UI components.

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt`

## Requirements
1. Add `chapterTotalSeconds: State<Int>` to `TextToSpeechSettingData`
2. Add `chapterElapsedSeconds: State<Int>` to `TextToSpeechSettingData`
3. Add `isCalculatingDuration: State<Boolean>` to `TextToSpeechSettingData`
4. Add `showRemainingTime: MutableState<Boolean>` to `TextToSpeechSettingData`
5. Add `seekToPosition: (Int) -> Unit` to `TextToSpeechSettingData`
6. Initialize these fields in the `state` initialization

## Implementation Steps

### Step 1: Add timing fields to TextToSpeechSettingData

Find the `TextToSpeechSettingData` data class and add these fields at the end:

```kotlin
@Stable
internal data class TextToSpeechSettingData(
    // ... existing fields ...
    val chapterTotalSeconds: State<Int>,
    val chapterElapsedSeconds: State<Int>,
    val isCalculatingDuration: State<Boolean>,
    val showRemainingTime: MutableState<Boolean>,
    val seekToPosition: (Int) -> Unit,
)
```

### Step 2: Update state initialization in ReaderTextToSpeech

Find the `val state = TextToSpeechSettingData(` initialization and add these fields at the end:

```kotlin
val state = TextToSpeechSettingData(
    // ... existing fields ...
    chapterTotalSeconds = chapterTotalSeconds,
    chapterElapsedSeconds = chapterElapsedSeconds,
    isCalculatingDuration = isCalculatingDuration,
    showRemainingTime = showRemainingTime,
    seekToPosition = ::seekToPosition,
)
```

## Verification
- File should compile without errors
- All timing states should be accessible through `state`
- `seekToPosition` function should be callable through `state.seekToPosition`

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt
git commit -m "feat(tts): expose timing states in TextToSpeechSettingData"
```
