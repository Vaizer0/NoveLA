# TTS Reader Playback Improvements — Design Spec

## Overview

Add YouTube-like seekable progress bar, accurate chapter duration calculation, and compact controls to the NoveLA TTS reader.

---

## 1. Duration Calculation Engine

### Data Model

```kotlin
// Per-chapter timing cache
val paragraphStartTimes: MutableMap<Int, List<Float>> = mutableMapOf()
// chapterIndex → list of start times (seconds) for each body paragraph
```

### Rules

- **Only body paragraphs** count toward duration (exclude `ReaderItem.Title`, `Divider`, `Padding`, `Image`, `BookStart`, `BookEnd`, `Error`, `Translating`, `GoogleTranslateAttribution`, `TranslateAttribution`, `Progressbar`)
- **Decorative-only lines** (lines with only `---`, `***`, `===`, `─`, `━`, etc.) excluded from character count
- **Inter-paragraph pause**: `500ms / voiceSpeed` added between each body paragraph
- **Chapter title**: Shows "Calculating..." text, elapsed stays at 0, seek bar at 0%

### Pre-calculation

When a chapter loads, build `paragraphStartTimes[chapterIndex]`:
```
para 0: 0.0s
para 1: (charCount[0] / (baseCPS * speed)) + (500 / speed / 1000)
para 2: para1_start + (charCount[1] / (baseCPS * speed)) + (500 / speed / 1000)
...
para N: total
```

### State Exposed (in `ReaderTextToSpeech`)

| State | Type | Description |
|-------|------|-------------|
| `chapterTotalSeconds` | `State<Int>` | Total body duration including pauses |
| `chapterElapsedSeconds` | `State<Int>` | Synced to current position |
| `isCalculatingDuration` | `State<Boolean>` | True during title |
| `showRemainingTime` | `MutableState<Boolean>` | Toggle for right-hand label |
| `seekToPosition(seconds: Int)` | Function | Seek function for bar interaction |

### Cleanup

When chapter finishes or user moves to another chapter, clear `paragraphStartTimes` for the old chapter. Only keep timing data for the current active chapter.

---

## 2. Mid-Chapter Resume

When `readChapterStartingFromItemIndex(itemIndex)` is called at a non-zero position:

1. Calculate total chapter time (or use cached value)
2. Look up `paragraphStartTimes[currentPosition]` for elapsed offset
3. Set `chapterElapsedSeconds` to that offset
4. Progress bar starts at correct position
5. TTS starts reading from beginning of current paragraph
6. Elapsed counts up from offset

**Title handling:**
- While on Title item → `isCalculatingDuration = true`, bar shows "..."
- First body paragraph hit → `isCalculatingDuration = false`, elapsed starts from 0

---

## 3. Seekable Progress Bar UI

### Layout (same row)

```
[Elapsed 1:23] ────●──────── [Total 5:30]
```

- **Left**: Elapsed time (min:sec format)
- **Center**: Thin bar (2-3dp height) with small round dot/thumb (12dp)
- **Right**: Total time (min:sec format) — tap to toggle remaining

### Toggle Behavior

- **Tap right time label** → toggles between `Total` (e.g., `5:30`) and `Remaining` (e.g., `-4:07`)
- Tapping again flips back
- Elapsed time stays as-is; only the right-hand label changes

### Seek Behavior

- **Drag dot** → seeks to that position in chapter
- **Tap bar** → seeks to tapped position
- Seek maps position (seconds) → find which paragraph that time falls in → seek to that paragraph's start

### When on Chapter Title

```
[0:00] ────●──────── [Calculating...]
```

### Colors

- Bar background: `onSurface.copy(alpha = 0.2f)`
- Bar filled: `MaterialTheme.colorScheme.primary`
- Dot: `MaterialTheme.colorScheme.primary`

---

## 4. Main Reader Section Layout

```
Row 1: [Start Here] [Focus] [Voices] [Saved Voices]   ← 4 buttons, single row
Row 2: [⏮ Ch] [⏮ Para] [▶/⏸] [⏭ Para] [⏭ Ch]      ← playback controls
Row 3: [1:23] ────●──────── [5:30]                     ← seek bar
```

### Button Details

**Row 1 (4 buttons):**
- Keep existing functionality unchanged
- Icon + label, evenly spaced, ~40dp tap target
- Order: Start Here, Focus, Voices, Saved Voices

**Row 2 (Playback controls):**
- Icon-only circles, 32dp
- Prev Chapter, Prev Paragraph, Play/Pause, Next Paragraph, Next Chapter

**Row 3 (Seek bar):**
- Height ~24dp (bar + times)
- Same row as times

---

## 5. Floating Mini TTS Layout

```
Row 1: [⏮] [▶/⏸] [⏭]              ← compact playback
Row 2: [1:23] ────●──────── [5:30]  ← thin seek bar
```

### Button Details

**Row 1:**
- Playback: 28dp icons
- Prev, Play/Pause, Next

**Row 2:**
- Seek bar: 16dp height total
- Same thin style as main reader

---

## 6. Lock Screen / Notification Controls

### Layout

```
[Book Title]
[Chapter Title]
[1:23] ────●──────── [5:30]
[⏮ Ch] [⏮ Para] [▶/⏸] [⏭ Para] [⏭ Ch]
```

### MediaSession Updates

- Report `METADATA_KEY_DURATION` = chapter total seconds (not -1)
- Report `PlaybackState` with actual position and playback speed
- Handle `ACTION_SEEK_TO` from lock screen
- All numbers must match in-app values

### Notification Updates

- Seek bar + playback controls only (no 4 buttons)
- Synced position/duration with in-app
- Position updates in real-time

---

## 7. Previous/Next Paragraph Behavior

### Existing Logic Preserved

- **Prev paragraph from title** → previous chapter's last paragraph
- **Next paragraph from last body** → next chapter's title
- **Prev/Next chapter buttons** → stay within chapter bounds (existing behavior)

### No Changes Needed

The existing `playPreviousItem()` and `playNextItem()` logic already handles chapter boundaries correctly. No modifications required.

---

## 8. Memory Management

- Only keep `paragraphStartTimes` for the current active chapter
- When chapter finishes or user moves to another chapter, clear old chapter's data
- No background memory waste
- One float per paragraph (~4 bytes × 100 paragraphs = 400 bytes per chapter)

---

## Files to Modify

| File | Changes |
|------|---------|
| `ReaderTextToSpeech.kt` | Add timing engine, paragraph start times cache, seek function, elapsed/total states |
| `TtsMiniPlayer.kt` | Add seek bar, compact layout, toggle remaining time |
| `NarratorMediaControlsNotification.kt` | Add seek bar, sync position/duration, handle seek |
| `NarratorMediaControlsCallback.kt` | Add seek handling |
| `ReaderScreen.kt` | Update main reader layout with 3-row design |
| `TtsProgressSeekBar.kt` | New file — reusable seek bar component |

---

## Acceptance Criteria

1. ✅ Progress bar is thin (2-3dp) with small dot (12dp), not thick
2. ✅ Elapsed and total times on same row as bar
3. ✅ Tap right time label toggles between total and remaining
4. ✅ Drag/tap bar seeks to position
5. ✅ Only body paragraphs counted in duration
6. ✅ Inter-paragraph pause: `500ms / voiceSpeed`
7. ✅ Chapter title shows "Calculating..."
8. ✅ Mid-chapter resume syncs to correct position
9. ✅ Notification shows same progress/duration as in-app
10. ✅ 4 buttons in single compact row
11. ✅ Previous/next paragraph can cross chapter boundaries
12. ✅ Old chapter timing data cleared when moving on
