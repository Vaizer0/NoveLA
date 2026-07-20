# Task 2 Report: Add Timing Engine to ReaderTextToSpeech

## What Was Implemented

Added a pre-calculated paragraph timing engine to `ReaderTextToSpeech.kt`:

1. **Paragraph start times cache**: `paragraphStartTimes` (`MutableMap<Int, List<Float>>`) mapping chapterIndex to list of start times in seconds for each text item
2. **Timing states**: `chapterTotalSeconds`, `chapterElapsedSeconds`, `isCalculatingDuration`, `showRemainingTime`
3. **`calculateParagraphStartTimes(chapterIndex)`**: Calculates start times for each paragraph using character count, base CPS, voice speed, and inter-paragraph pause of `500ms / voiceSpeed`
4. **`getElapsedTimeForPosition(chapterIndex, chapterItemPosition)`**: Returns the start time for a given paragraph position
5. **`seekToPosition(seconds: Int)`**: Finds the paragraph at the target time and restarts playback from there
6. **`clearChapterTiming(chapterIndex: Int)`**: Removes cached timing data for a chapter
7. **Updated `readChapterStartingFromItemIndex`**: Calculates paragraph start times on chapter load, sets initial elapsed time, and shows "Calculating..." state for chapter titles
8. **Elapsed time tracking coroutine in init block**: Updates `chapterElapsedSeconds` continuously while playing using `currentTextSpeakFlow`

## Files Changed

- `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt` (+137 lines)

## Self-Review Findings

All requirements from the task brief have been implemented exactly as specified:
- Timing cache uses `MutableMap<Int, List<Float>>` as specified
- Inter-paragraph pause is `500ms / voiceSpeed` as required
- Only `ReaderItem.Text` items are counted for timing (Divider, Padding excluded by type)
- Mid-chapter resume syncs elapsed via `getElapsedTimeForPosition`
- Chapter title sets `isCalculatingDuration = true` for "Calculating..." display
- No new imports needed (all types already imported)

## Issues or Concerns

None. The implementation follows the task brief exactly and uses existing code patterns.
