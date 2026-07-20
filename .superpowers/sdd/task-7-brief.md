# Task 7: Clean Up Old Chapter Timing Data

## Context
This is Task 7 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

Tasks 1-6 completed the main features. This task ensures old chapter timing data is cleaned up.

## Goal
Clear `paragraphStartTimes` for old chapters when the user moves to a new chapter.

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/manager/ReaderSession.kt`

## Requirements
1. Clear timing data when chapter ends (reachedChapterEndFlowChapterIndex)
2. Clear timing data when user navigates to a different chapter (updateInfoViewTo)
3. Only keep timing data for the current active chapter

## Implementation Steps

### Step 1: Add cleanup in reachedChapterEndFlowChapterIndex collection

Find the existing collector for `reachedChapterEndFlowChapterIndex` and add cleanup at the start:

```kotlin
scope.launch {
    readerTextToSpeech.reachedChapterEndFlowChapterIndex.collect { chapterIndex ->
        // Clear timing data for completed chapter
        readerTextToSpeech.clearChapterTiming(chapterIndex)
        
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                // ... existing chapter transition logic ...
            }
        }
    }
}
```

### Step 2: Add cleanup when user navigates away from chapter

In `updateInfoViewTo`, find the section that checks `if (chapterIndex != lastChapterIndex)` and add cleanup:

```kotlin
if (chapterIndex != lastChapterIndex) {
    // Clear timing data for old chapter
    if (lastChapterIndex >= 0) {
        readerTextToSpeech.clearChapterTiming(lastChapterIndex)
    }
    // ... existing logic ...
}
```

## Verification
- File should compile without errors
- Old chapter timing data should be cleared when moving to new chapter
- No memory leaks from accumulated timing data

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/manager/ReaderSession.kt
git commit -m "feat(tts): clean up old chapter timing data on chapter change"
```
