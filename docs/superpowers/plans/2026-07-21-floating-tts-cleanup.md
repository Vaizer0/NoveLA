# Floating TTS Menu Cleanup Implementation Plan

**Goal:** Remove paragraph toggle (always on), thin YouTube-style sliders, remove TTS timer/duration, add chapter % progress.

**Architecture:** Three groups of changes: (1) remove showText/showParagraph toggle from service → overlay → miniplayer; (2) replace standard Slider with custom thin-line slider; (3) remove all timer-related fields from ReaderTextToSpeech → TextToSpeechSettingData and add chapterProgressPercent.

**Tech Stack:** Kotlin, Jetpack Compose, Android

---
### Task 1: Remove showText/paragraph toggle from service + overlay

**Files:**
- Modify: `FloatingTtsService.kt` — remove `showText` state and `showTextToggle`/`onShowTextToggle` params
- Modify: `FloatingTtsOverlay.kt` — remove `showTextToggle`/`onShowTextToggle` params

- [ ] Remove `var showText = mutableStateOf(false)` from `FloatingTtsService` companion
- [ ] Remove `showTextToggle` and `onShowTextToggle` from `FloatingTtsOverlayContent` call in service's `setContent`
- [ ] Remove `showTextToggle: Boolean` and `onShowTextToggle: (() -> Unit)?` params from `FloatingTtsOverlayContent` signature
- [ ] Remove passing of `showTextToggle` and `onShowTextToggle` to `TtsMiniPlayer` call inside overlay
- [ ] Commit

### Task 2: Remove showText/paragraph toggle from TtsMiniPlayer

**Files:**
- Modify: `TtsMiniPlayer.kt`

- [ ] Remove `showTextToggle: Boolean` and `onShowTextToggle: (() -> Unit)?` from `TtsMiniPlayer` composable params
- [ ] Remove passing of these params to `FloatingTtsMiniPlayer`
- [ ] Remove `showTextToggle: Boolean` and `onShowTextToggle: (() -> Unit)?` from `FloatingTtsMiniPlayer` composable params
- [ ] Remove the trailingAction text toggle icon button block in `MiniPlayerControls` call
- [ ] In `FloatingTtsMiniPlayer` body, change `val hasParagraphText = showParagraphText && ...` to `val hasParagraphText = displayText.isNotBlank() || isBothMode` (always on, no `showParagraphText` check)
- [ ] Remove `showParagraphText` parameter from `FloatingTtsMiniPlayer` and `TtsMiniPlayer`
- [ ] Remove `onShowTextToggle` param from trailingAction lambda
- [ ] Remove the unused `Icons.AutoMirrored.Rounded.TextSnippet` import if no longer used
- [ ] Commit

### Task 3: Create custom thin slider and replace sliders

**Files:**
- Modify: `TtsMiniPlayer.kt` — add `ThinSlider` composable, replace opacity and width sliders

- [ ] Add a `@Composable fun ThinSlider(...)` after `HighlightedText` at bottom of file:
```kotlin
@Composable
private fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { 2.dp.toPx() }
    val thumbRadiusPx = with(density) { 4.dp.toPx() }

    Box(
        modifier = modifier
            .height(20.dp)
            .clipToBounds()
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val width = size.width.toFloat()
                    val fraction = (change.position.x / width).coerceIn(0f, 1f)
                    val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                    onValueChange(newValue)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().align(Alignment.Center)) {
            val width = size.width
            val centerY = size.height / 2

            val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val activeWidth = width * fraction

            drawRoundRect(
                color = Color.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                topLeft = Offset.Zero,
                size = Size(width, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2)
            )
            drawRoundRect(
                color = Color.MaterialTheme.colorScheme.primary,
                topLeft = Offset.Zero,
                size = Size(activeWidth, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2)
            )
            drawCircle(
                color = Color.MaterialTheme.colorScheme.primary,
                radius = thumbRadiusPx,
                center = Offset(activeWidth, centerY)
            )
        }
    }
}
```
- [ ] Replace both standard `Slider` composables (opacity and width) with `ThinSlider`
- [ ] Add missing imports: `clipToBounds`, `Canvas`, `detectDragGestures`, `LocalDensity`, `Offset`, `Size`, `CornerRadius`, `drawCircle`, `drawRoundRect`
- [ ] Remove unused `Slider` and `SliderDefaults` imports
- [ ] Commit

### Task 4: Remove TTS timer/duration bloat from ReaderTextToSpeech

**Files:**
- Modify: `ReaderTextToSpeech.kt`

- [ ] Remove from `TextToSpeechSettingData` data class:
  - `chapterWordCount: State<Int>`
  - `remainingWordCount: State<Int>`
  - `estimatedWpm: State<Int>`
  - `estimatedTotalSeconds: State<Int>`
  - `estimatedRemainingSeconds: State<Int>`
- [ ] Add to `TextToSpeechSettingData`: `chapterProgressPercent: State<Int>`
- [ ] Remove the following derived states from `ReaderTextToSpeech`:
  - `chapterWordCount`
  - `remainingWordCount`
  - `chapterCharacterCount`
  - `remainingCharacterCount`
  - `estimatedWpm`
  - `estimatedTotalSeconds`
  - `estimatedRemainingSeconds`
- [ ] Remove `baseCharactersPerSecond` mutable state (line 148)
- [ ] Remove the CPS calibration coroutine in `init` block (lines 311-350)
- [ ] Add `chapterProgressPercent` derived state:
```kotlin
val chapterProgressPercent = derivedStateOf {
    val currentItemPos = manager.currentActiveItemState.value.itemPos
    val chapterIndex = currentItemPos.chapterIndex
    val currentPos = currentItemPos.chapterItemPosition
    val itemsInChapter = items.filterIsInstance<ReaderItem.Position>()
        .filter { it.chapterIndex == chapterIndex }
    if (itemsInChapter.isEmpty()) 0
    else ((currentPos.toFloat() / itemsInChapter.size) * 100).toInt().coerceIn(0, 100)
}
```
- [ ] Update `state` object to include `chapterProgressPercent` and exclude removed fields
- [ ] Commit

### Task 5: Remove timer UI, add chapter % in MiniPlayerControls

**Files:**
- Modify: `TtsMiniPlayer.kt` — `MiniPlayerControls` and callers

- [ ] Remove `animatedProgress: Float` param from `MiniPlayerControls`
- [ ] Remove `remaining: Int` param from `MiniPlayerControls`
- [ ] Remove the progress bar Box (lines 187-201) from `MiniPlayerControls`
- [ ] Remove the time display Surface (lines 203-225) from `MiniPlayerControls`
- [ ] Remove `formatDuration()` function at bottom of file
- [ ] Remove `Icons.Rounded.AccessTime` import if no longer used
- [ ] In `FloatingTtsMiniPlayer`, remove `val total`, `val remaining`, `val animatedProgress` calculations
- [ ] In `FloatingTtsMiniPlayer`, pass chapter % text to `MiniPlayerControls` or render inline
- [ ] Add chapter % display — a simple Text composable replacing the time area:
```kotlin
Text(
    text = "${state.chapterProgressPercent.value}%",
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```
- [ ] Pass `state.chapterProgressPercent` to the MiniPlayerControls row or update the existing spot
- [ ] Commit

### Task 6: Build APK

- [ ] Push branch to GitHub
- [ ] Run build workflow: `gh workflow run buildRelease.yml --repo Vaizer0/NoveLA --ref feature/tts-menu-reorg -f build_type=test`
- [ ] Verify build passes
