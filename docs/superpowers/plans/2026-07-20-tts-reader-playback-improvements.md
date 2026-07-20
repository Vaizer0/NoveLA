# TTS Reader Playback Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add YouTube-like seekable progress bar, accurate chapter duration calculation, and compact controls to the NoveLA TTS reader.

**Architecture:** Pre-calculate paragraph start times per chapter, expose elapsed/total states, create reusable seek bar component, update mini player and notification to use new progress bar.

**Tech Stack:** Kotlin, Jetpack Compose, Android MediaSession, TextToSpeech API

## Global Constraints

- Do NOT touch, edit, commit to, or push the `default` branch
- Create a NEW branch off `default` for all work
- Build ONLY through GitHub Actions workflow
- Do NOT trigger build until code is written AND 3 review cycles complete
- Always build the feature branch, never `default`

---

## File Structure

| File | Responsibility |
|------|----------------|
| `TtsProgressSeekBar.kt` | Reusable thin seek bar component with elapsed/total times |
| `ReaderTextToSpeech.kt` | Add timing engine, paragraph start times cache, seek function |
| `TtsMiniPlayer.kt` | Integrate seek bar, compact layout |
| `NarratorMediaControlsNotification.kt` | Add seek bar, sync position/duration |
| `NarratorMediaControlsCallback.kt` | Add seek handling |
| `ReaderScreen.kt` | Update main reader layout with 3-row design |

---

## Task 1: Create Reusable TTS Progress Seek Bar Component

**Files:**
- Create: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressSeekBar.kt`

**Interfaces:**
- Consumes: `elapsed: Int`, `total: Int`, `isCalculating: Boolean`, `showRemaining: Boolean`, `onSeek: (Int) -> Unit`, `onToggleRemaining: () -> Unit`
- Produces: Composable UI element

- [ ] **Step 1: Create the TtsProgressSeekBar composable**

```kotlin
package my.noveldokusha.features.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
) {
    val progress = if (total > 0) elapsed.toFloat() / total else 0f
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val displayProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        // Elapsed time
        Text(
            text = formatDuration(elapsed),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { /* no action on elapsed */ }
        )

        // Seek bar
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(barHeight + thumbSize)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val barWidth = size.width.toFloat()
                            val tapPosition = (offset.x / barWidth).coerceIn(0f, 1f)
                            val seekSeconds = (tapPosition * total).toInt()
                            onSeek(seekSeconds)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val barWidth = size.width.toFloat()
                            dragProgress = (offset.x / barWidth).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val barWidth = size.width.toFloat()
                            dragProgress = (change.position.x / barWidth).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            val seekSeconds = (dragProgress * total).toInt()
                            onSeek(seekSeconds)
                        }
                    )
                }
        ) {
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
            )
            // Filled track
            Box(
                modifier = Modifier
                    .fillMaxWidth(displayProgress)
                    .height(barHeight)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Thumb
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .align(Alignment.CenterStart)
                    .offset(x = (displayProgress * (size.width - thumbSize.toPx())).toDp())
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        // Total/Remaining time
        Text(
            text = if (isCalculating) "..." else {
                val displaySeconds = if (showRemaining) -(total - elapsed) else total
                formatDuration(displaySeconds)
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { onToggleRemaining() }
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val absSeconds = kotlin.math.abs(seconds)
    val prefix = if (seconds < 0) "-" else ""
    val h = absSeconds / 3600
    val m = (absSeconds % 3600) / 60
    val s = absSeconds % 60
    return if (h > 0) {
        "${prefix}${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${prefix}${m}:${s.toString().padStart(2, '0')}"
    }
}
```

- [ ] **Step 2: Verify the file compiles (conceptual check)**

- [ ] **Step 3: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressSeekBar.kt
git commit -m "feat(tts): add reusable TtsProgressSeekBar component"
```

---

## Task 2: Add Timing Engine to ReaderTextToSpeech

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt`

**Interfaces:**
- Consumes: `items: List<ReaderItem>`, `manager.voiceSpeed`, `baseCharactersPerSecond`
- Produces: `chapterTotalSeconds`, `chapterElapsedSeconds`, `isCalculatingDuration`, `showRemainingTime`, `seekToPosition()`, `paragraphStartTimes` cache

- [ ] **Step 1: Add paragraph start times cache and timing states**

Add these properties to `ReaderTextToSpeech` class:

```kotlin
// Per-chapter timing cache: chapterIndex → list of start times (seconds)
private val paragraphStartTimes = mutableMapOf<Int, List<Float>>()

// Timing states
val chapterTotalSeconds = mutableStateOf(0)
val chapterElapsedSeconds = mutableStateOf(0)
val isCalculatingDuration = mutableStateOf(true)
val showRemainingTime = mutableStateOf(false)
```

- [ ] **Step 2: Add method to calculate and cache paragraph start times**

```kotlin
private fun calculateParagraphStartTimes(chapterIndex: Int) {
    val chapterItems = items
        .filterIsInstance<ReaderItem.Text>()
        .filter { it.chapterIndex == chapterIndex }
    
    val startTimes = mutableListOf<Float>()
    var currentTime = 0f
    val speed = manager.voiceSpeed.floatValue
    val pauseMs = 500f / speed
    val pauseSec = pauseMs / 1000f
    
    for ((index, item) in chapterItems.withIndex()) {
        startTimes.add(currentTime)
        
        val text = item.textToDisplay
        val charCount = text.length
        val cps = baseCharactersPerSecond.value * speed
        
        if (cps > 0f && charCount > 0) {
            currentTime += charCount / cps
        }
        
        // Add pause after paragraph (except last)
        if (index < chapterItems.lastIndex) {
            currentTime += pauseSec
        }
    }
    
    paragraphStartTimes[chapterIndex] = startTimes
    
    // Update total
    chapterTotalSeconds.value = currentTime.toInt()
}
```

- [ ] **Step 3: Add method to get elapsed time for a paragraph position**

```kotlin
private fun getElapsedTimeForPosition(chapterIndex: Int, chapterItemPosition: Int): Int {
    val times = paragraphStartTimes[chapterIndex] ?: return 0
    val index = times.indices.find { i ->
        val item = items.filterIsInstance<ReaderItem.Text>()
            .filter { it.chapterIndex == chapterIndex }
            .getOrNull(i)
        item?.chapterItemPosition == chapterItemPosition
    } ?: return 0
    return times[index].toInt()
}
```

- [ ] **Step 4: Add seek function**

```kotlin
fun seekToPosition(seconds: Int) {
    val currentChapterIndex = currentTextPlaying.value.itemPos.chapterIndex
    val times = paragraphStartTimes[currentChapterIndex] ?: return
    
    // Find which paragraph contains this time
    val targetTime = seconds.toFloat()
    var paragraphIndex = 0
    for (i in times.indices) {
        if (times[i] <= targetTime) {
            paragraphIndex = i
        } else {
            break
        }
    }
    
    val chapterItems = items
        .filterIsInstance<ReaderItem.Text>()
        .filter { it.chapterIndex == currentChapterIndex }
    
    val targetItem = chapterItems.getOrNull(paragraphIndex) ?: return
    
    // Seek to that paragraph
    stop()
    start()
    readChapterStartingFromItemIndex(
        itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = currentChapterIndex,
            chapterItemPosition = targetItem.chapterItemPosition
        ),
        chapterIndex = currentChapterIndex
    )
}
```

- [ ] **Step 5: Update elapsed time tracking in init block**

Add to the existing calibration coroutine to also update elapsed time:

```kotlin
// Update elapsed time while playing
coroutineScope.launch {
    var elapsedOffset = 0
    var startTime = 0L
    
    manager.currentTextSpeakFlow.collect { utterance ->
        when (utterance.playState) {
            Utterance.PlayState.PLAYING -> {
                if (startTime == 0L) {
                    startTime = System.currentTimeMillis()
                    // Set initial elapsed from paragraph start times
                    val chapterIndex = utterance.itemPos.chapterIndex
                    val itemPos = utterance.itemPos.chapterItemPosition
                    elapsedOffset = getElapsedTimeForPosition(chapterIndex, itemPos)
                    chapterElapsedSeconds.value = elapsedOffset
                    isCalculatingDuration.value = false
                }
            }
            Utterance.PlayState.FINISHED -> {
                if (startTime > 0) {
                    val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                    chapterElapsedSeconds.value = elapsedOffset + elapsed
                    startTime = 0L
                }
            }
            else -> Unit
        }
    }
}
```

- [ ] **Step 6: Update readChapterStartingFromItemIndex to calculate timing**

```kotlin
suspend fun readChapterStartingFromItemIndex(
    itemIndex: Int,
    chapterIndex: Int,
) = withContext(Dispatchers.Main.immediate) {
    // Calculate paragraph start times if not cached
    if (!paragraphStartTimes.containsKey(chapterIndex)) {
        calculateParagraphStartTimes(chapterIndex)
    }
    
    // Set initial elapsed from paragraph start times
    val item = items.getOrNull(itemIndex) as? ReaderItem.Text
    if (item != null) {
        chapterElapsedSeconds.value = getElapsedTimeForPosition(chapterIndex, item.chapterItemPosition)
        isCalculatingDuration.value = false
    } else if (items.getOrNull(itemIndex) is ReaderItem.Title) {
        isCalculatingDuration.value = true
        chapterElapsedSeconds.value = 0
    }
    
    val nextItems = getChapterNextItems(
        itemIndex = itemIndex,
        chapterIndex = chapterIndex,
        quantity = halfBuffer * 2
    )

    if (nextItems.isEmpty()) {
        reachedChapterEndFlowChapterIndex.emit(chapterIndex)
        return@withContext
    }

    val firstItem = nextItems.first()
    manager.setCurrentSpeakState(
        TextSynthesis(
            itemPos = firstItem,
            playState = Utterance.PlayState.LOADING
        )
    )

    nextItems.forEach(::speakItem)
}
```

- [ ] **Step 7: Add cleanup when chapter changes**

Add to the existing `reachedChapterEndFlowChapterIndex` collection in `ReaderSession`:

```kotlin
// Clear timing data for completed chapter
scope.launch {
    readerTextToSpeech.reachedChapterEndFlowChapterIndex.collect { chapterIndex ->
        readerTextToSpeech.clearChapterTiming(chapterIndex)
    }
}
```

And add the method to `ReaderTextToSpeech`:

```kotlin
fun clearChapterTiming(chapterIndex: Int) {
    paragraphStartTimes.remove(chapterIndex)
}
```

- [ ] **Step 8: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt
git commit -m "feat(tts): add timing engine with paragraph start times cache"
```

---

## Task 3: Update TextToSpeechSettingData to Expose Timing States

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt`

**Interfaces:**
- Consumes: New timing states from Task 2
- Produces: Updated `TextToSpeechSettingData` with timing fields

- [ ] **Step 1: Add timing fields to TextToSpeechSettingData**

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

- [ ] **Step 2: Update state initialization in ReaderTextToSpeech**

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

- [ ] **Step 3: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt
git commit -m "feat(tts): expose timing states in TextToSpeechSettingData"
```

---

## Task 4: Integrate Seek Bar into TtsMiniPlayer

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt`

**Interfaces:**
- Consumes: `TtsProgressSeekBar` from Task 1, timing states from Task 3
- Produces: Updated mini player with seek bar

- [ ] **Step 1: Add seek bar to FloatingTtsMiniPlayer**

Replace the existing progress bar section with:

```kotlin
// In FloatingTtsMiniPlayer, after MiniPlayerControls:
TtsProgressSeekBar(
    elapsed = state.chapterElapsedSeconds.value,
    total = state.chapterTotalSeconds.value,
    isCalculating = state.isCalculatingDuration.value,
    showRemaining = state.showRemainingTime.value,
    onSeek = { state.seekToPosition(it) },
    onToggleRemaining = { state.showRemainingTime.value = !state.showRemainingTime.value },
    barHeight = 2.dp,
    thumbSize = 10.dp,
    modifier = Modifier.padding(horizontal = 4.dp)
)
```

- [ ] **Step 2: Remove old progress bar code**

Remove the old `Box` with `animatedProgress` from `MiniPlayerControls`.

- [ ] **Step 3: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt
git commit -m "feat(tts): integrate seek bar into mini player"
```

---

## Task 5: Update Main Reader Layout with 3-Row Design

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt`

**Interfaces:**
- Consumes: `TtsProgressSeekBar` from Task 1, timing states from Task 3
- Produces: Updated main reader TTS controls

- [ ] **Step 1: Add 3-row layout to reader TTS controls**

Find the existing TTS controls section and replace with:

```kotlin
// Row 1: 4 buttons in single row
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly
) {
    // Start Here button
    // Focus button
    // Voices button
    // Saved Voices button
}

// Row 2: Playback controls
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
) {
    // Prev Chapter
    // Prev Paragraph
    // Play/Pause
    // Next Paragraph
    // Next Chapter
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

- [ ] **Step 2: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt
git commit -m "feat(tts): update main reader layout with 3-row design"
```

---

## Task 6: Update Notification with Seek Bar

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsNotification.kt`

**Interfaces:**
- Consumes: `chapterTotalSeconds`, `chapterElapsedSeconds` from Task 3
- Produces: Updated notification with seek bar and synced position

- [ ] **Step 1: Update MediaSession metadata with duration**

Replace the existing `refreshMediaSessionMetadata`:

```kotlin
private fun refreshMediaSessionMetadata() {
    val builder = MediaMetadataCompat.Builder()
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 
            (readerManager.session?.readerTextToSpeech?.chapterTotalSeconds?.value?.toLong() ?: -1L) * 1000)
    currentChapterTitle?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
    currentBookTitle?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
    currentCoverBitmap?.let {
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
    }
    mediaSession?.setMetadata(builder.build())
}
```

- [ ] **Step 2: Update PlaybackState with position**

Add position tracking to the playback state updates:

```kotlin
// In the scope.launch for isPlaying updates:
val position = readerSession.readerTextToSpeech.chapterElapsedSeconds.value.toLong() * 1000
val stateBuilder = PlaybackStateCompat.Builder()
    .setActions(/* ... */)
    .setState(playbackState, position, if (isPlaying) 1.0f else 0.0f)
    .setActivePlaybackSpeed(readerSession.readerTextToSpeech.state.voiceSpeed.value)
this@NarratorMediaControlsNotification.mediaSession?.setPlaybackState(stateBuilder.build())
```

- [ ] **Step 3: Add seek handling to callback**

In `NarratorMediaControlsCallback.kt`, add:

```kotlin
override fun onSeekTo(pos: Long) {
    val seconds = (pos / 1000).toInt()
    readerTextToSpeech.seekToPosition(seconds)
}
```

- [ ] **Step 4: Update notification text to show progress**

Update the progress text to show elapsed/total:

```kotlin
// In the scope.launch for progress updates:
val elapsed = readerSession.readerTextToSpeech.chapterElapsedSeconds.value
val total = readerSession.readerTextToSpeech.chapterTotalSeconds.value
val progressText = if (total > 0) {
    "${formatDuration(elapsed)} / ${formatDuration(total)}"
} else {
    context.getString(R.string.progress_x_percentage, stats.chapterReadPercentage())
}
notificationsCenter.modifyNotification(/* ... */) {
    text = "$chapterPos  $progressText"
}
```

- [ ] **Step 5: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsNotification.kt
git add features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsCallback.kt
git commit -m "feat(tts): add seek bar and position sync to notification"
```

---

## Task 7: Clean Up Old Chapter Timing Data

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/manager/ReaderSession.kt`

**Interfaces:**
- Consumes: `clearChapterTiming()` from Task 2
- Produces: Automatic cleanup when chapter changes

- [ ] **Step 1: Add cleanup in reachedChapterEndFlowChapterIndex collection**

Update the existing collector:

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

- [ ] **Step 2: Add cleanup when user navigates away from chapter**

In `updateInfoViewTo`:

```kotlin
if (chapterIndex != lastChapterIndex) {
    // Clear timing data for old chapter
    if (lastChapterIndex >= 0) {
        readerTextToSpeech.clearChapterTiming(lastChapterIndex)
    }
    // ... existing logic ...
}
```

- [ ] **Step 3: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/manager/ReaderSession.kt
git commit -m "feat(tts): clean up old chapter timing data on chapter change"
```

---

## Task 8: Verify Previous/Next Paragraph Behavior

**Files:**
- Review: `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt`

**Interfaces:**
- No changes needed - verify existing behavior

- [ ] **Step 1: Review playPreviousItem and playNextItem methods**

Verify that:
- `playPreviousItem()` can cross chapter boundaries (prev from title → previous chapter's last paragraph)
- `playNextItem()` can cross chapter boundaries (next from last body → next chapter's title)
- Both methods stay within chapter bounds when using prev/next chapter buttons

- [ ] **Step 2: No code changes needed if behavior is correct**

- [ ] **Step 3: Commit (no-op if no changes)**

```bash
# No commit needed if no changes
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-20-tts-reader-playback-improvements.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
