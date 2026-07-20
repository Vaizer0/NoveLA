# Task 7: Clean Up Old Chapter Timing Data

## What Was Implemented

Added `clearChapterTiming` calls in two places to prevent accumulated timing data from old chapters:

1. **`reachedChapterEndFlowChapterIndex` collector** (line 272): Clears timing data for the completed chapter when TTS reaches the end of a chapter.

2. **`updateInfoViewTo` chapter change block** (lines 422-424): Clears timing data for the old chapter when the user navigates to a different chapter (`chapterIndex != lastChapterIndex`).

## Files Changed

- `features/reader/src/main/java/my/noveldokusha/features/reader/manager/ReaderSession.kt` (4 lines added)

## Self-Review Findings

- Changes are minimal and correct
- Guard `lastChapterIndex >= 0` prevents clearing on initial load (-1)
- No build tools available in this environment to verify compilation, but the changes are straightforward single-line additions that call an existing function

## Commit

- **af6174a9** - `feat(tts): clean up old chapter timing data on chapter change`
