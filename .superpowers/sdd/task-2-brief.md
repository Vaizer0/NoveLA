# Task 2: Add Timing Engine to ReaderTextToSpeech

## Context
This is Task 2 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

Task 1 created the `TtsProgressSeekBar.kt` component. This task adds the timing engine to `ReaderTextToSpeech.kt`.

## Goal
Add pre-calculated paragraph start times, elapsed/total states, seek function, and timing calculation to `ReaderTextToSpeech`.

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt`

## Requirements
1. Add `paragraphStartTimes` cache: `MutableMap<Int, List<Float>>` mapping chapterIndex to list of start times (seconds) for each body paragraph
2. Add timing states: `chapterTotalSeconds`, `chapterElapsedSeconds`, `isCalculatingDuration`, `showRemainingTime`
3. Add `seekToPosition(seconds: Int)` function
4. Add `clearChapterTiming(chapterIndex: Int)` function
5. Calculate paragraph start times when chapter loads
6. Only count body paragraphs (exclude Title, Divider, Padding, etc.)
7. Inter-paragraph pause: `500ms / voiceSpeed` between each body paragraph
8. Update elapsed time while playing
9. Handle mid-chapter resume correctly
10. Handle chapter title showing "Calculating..."

## Implementation Steps

### Step 1: Add paragraph start times cache and timing states

Add these properties to `ReaderTextToSpeech` class (after the existing `baseCharactersPerSecond`):

```kotlin
// Per-chapter timing cache: chapterIndex → list of start times (seconds)
private val paragraphStartTimes = mutableMapOf<Int, List<Float>>()

// Timing states
val chapterTotalSeconds = mutableStateOf(0)
val chapterElapsedSeconds = mutableStateOf(0)
val isCalculatingDuration = mutableStateOf(true)
val showRemainingTime = mutableStateOf(false)
```

### Step 2: Add method to calculate and cache paragraph start times

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

### Step 3: Add method to get elapsed time for a paragraph position

```kotlin
private fun getElapsedTimeForPosition(chapterIndex: Int, chapterItemPosition: Int): Int {
    val times = paragraphStartTimes[chapterIndex] ?: return 0
    val chapterItems = items
        .filterIsInstance<ReaderItem.Text>()
        .filter { it.chapterIndex == chapterIndex }
    
    val index = chapterItems.indexOfFirst { it.chapterItemPosition == chapterItemPosition }
    if (index < 0 || index >= times.size) return 0
    return times[index].toInt()
}
```

### Step 4: Add seek function

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

### Step 5: Add cleanup function

```kotlin
fun clearChapterTiming(chapterIndex: Int) {
    paragraphStartTimes.remove(chapterIndex)
}
```

### Step 6: Update readChapterStartingFromItemIndex to calculate timing

Replace the existing `readChapterStartingFromItemIndex` method:

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

### Step 7: Add elapsed time tracking in init block

Add this coroutine to the existing `init` block (after the existing calibration coroutine):

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

## Verification
- File should compile without errors
- Timing calculation should use only body paragraphs
- Inter-paragraph pause should be `500ms / voiceSpeed`
- Mid-chapter resume should sync elapsed to correct position
- Chapter title should show "Calculating..."

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt
git commit -m "feat(tts): add timing engine with paragraph start times cache"
```
