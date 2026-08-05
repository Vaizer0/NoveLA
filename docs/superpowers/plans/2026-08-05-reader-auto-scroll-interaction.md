# Reader Auto-Scroll Interaction Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop TTS follow-scroll from fighting the user while they touch or manually scroll the reader list.

**Architecture:** Introduce a small, pure `ReaderInteractionGate` state holder that combines finger-down touch state, ListView scroll state, and a short post-interaction grace period. Wire it into `ReaderActivity`'s touch/scroll listeners and make the follow-scroll guard consult it, while keeping forced-scroll and highlight-rebind behavior intact.

**Tech Stack:** Kotlin, Android `ListView` (`AbsListView`), JUnit 4, Gradle (Android library module `features/reader`).

## Global Constraints

- Only `ReaderActivity.kt` in the reader feature and one new gate class + one new test file change behavior. Do not touch TTS playback, highlighting, adapter rendering, `ReaderTextToSpeech.kt`, `ReaderSession.kt`, or `ReaderManager.kt`.
- Forced scroll paths (Focus button via `scrollToReaderItem`, TTS prev/next, `scrollToChapterTop`, `ttsScrolledToTheTop/Bottom`, `onResume`) must remain forced/unmodified.
- The highlight rebind in `scrollToReadingPositionOptional` must keep running while the user interacts.
- No workflow files are modified; APK verification uses the existing `buildRelease.yml` with `build_type: test`.
- Grace period is a single constant `GRACE_MS = 1500L`.
- Follow existing style: package `my.noveldokusha.features.reader`, Timber for logging, no comments unless needed for subtle state logic (existing file uses Russian comments).

---

### Task 1: Add `ReaderInteractionGate` and its unit test

**Files:**
- Create: `features/reader/src/main/java/my/noveldokusha/features/reader/ReaderInteractionGate.kt`
- Test: `features/reader/src/test/java/ReaderInteractionGateTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin, no Android deps — but it references `MotionEvent` constants by value).
- Produces:
  - `class ReaderInteractionGate`
  - `fun onTouch(actionMasked: Int, pointerCount: Int, now: Long)`
  - `fun onScroll(now: Long)`
  - `fun onScrollStateChanged(isScrolling: Boolean, now: Long)`
  - `fun isUserInteracting(now: Long): Boolean`
  - `val userIsTouching: Boolean` (read-only public getter)

- [ ] **Step 1: Write the failing test**

```kotlin
package my.noveldokusha.features.reader

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionGateTest {

    private val gate = ReaderInteractionGate()

    private fun down(now: Long) = gate.onTouch(MotionEvent.ACTION_DOWN, pointerCount = 1, now = now)
    private fun up(now: Long) = gate.onTouch(MotionEvent.ACTION_UP, pointerCount = 1, now = now)

    @Test
    fun `idle after grace is not interacting`() {
        assertFalse(gate.isUserInteracting(now = 0L))
        assertFalse(gate.isUserInteracting(now = 10_000L))
    }

    @Test
    fun `touching blocks auto-scroll even without movement`() {
        down(now = 100L)
        assertTrue(gate.isUserInteracting(now = 100L))
        // Finger held still long after grace would have expired must stay blocking.
        assertTrue(gate.isUserInteracting(now = 10_000L))
        up(now = 10_000L)
        // Grace period keeps the gate closed right after the release...
        assertTrue(gate.isUserInteracting(now = 10_000L))
        // ...and only expires 1500ms after the last interaction event.
        assertFalse(gate.isUserInteracting(now = 11_500L))
    }

    @Test
    fun `scroll state blocks auto-scroll`() {
        gate.onScrollStateChanged(isScrolling = true, now = 0L)
        assertTrue(gate.isUserInteracting(now = 100L))
        gate.onScrollStateChanged(isScrolling = false, now = 100L)
        // Grace period still blocks shortly after the scroll ends.
        assertTrue(gate.isUserInteracting(now = 1_500L))
        assertFalse(gate.isUserInteracting(now = 1_600L))
    }

    @Test
    fun `onScroll refreshes the grace period`() {
        gate.onScrollStateChanged(isScrolling = true, now = 0L)
        gate.onScrollStateChanged(isScrolling = false, now = 0L)
        gate.onScroll(now = 1_000L)
        assertTrue(gate.isUserInteracting(now = 2_400L))
        assertFalse(gate.isUserInteracting(now = 2_600L))
    }

    @Test
    fun `pointer up while another pointer remains keeps touching`() {
        gate.onTouch(MotionEvent.ACTION_POINTER_DOWN, pointerCount = 2, now = 0L)
        gate.onTouch(MotionEvent.ACTION_POINTER_UP, pointerCount = 2, now = 100L)
        // One finger is still down, so the gate must keep blocking.
        assertTrue(gate.isUserInteracting(now = 100L))
        gate.onTouch(MotionEvent.ACTION_UP, pointerCount = 1, now = 200L)
        assertFalse(gate.userIsTouching)
        assertTrue(gate.isUserInteracting(now = 200L))
        assertFalse(gate.isUserInteracting(now = 1_700L))
    }

    @Test
    fun `cancel clears touching`() {
        down(now = 0L)
        gate.onTouch(MotionEvent.ACTION_CANCEL, pointerCount = 1, now = 10L)
        assertFalse(gate.userIsTouching)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :features:reader:testDebugUnitTest`
Expected: FAIL — `ReaderInteractionGate` unresolved.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package my.noveldokusha.features.reader

import android.view.MotionEvent

/**
 * Tracks whether the user is currently touching or manually scrolling the reader
 * list. TTS follow-scroll must be suppressed while this returns true, otherwise
 * the reader yanks the view back to the spoken paragraph mid-gesture.
 */
class ReaderInteractionGate {

    var userIsTouching = false
        private set

    private var isScrolling = false
    private var lastInteractionTime: Long? = null

    fun onTouch(actionMasked: Int, pointerCount: Int, now: Long) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> userIsTouching = true

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> userIsTouching = false

            MotionEvent.ACTION_POINTER_UP ->
                if (pointerCount <= 1) userIsTouching = false
        }
        lastInteractionTime = now
    }

    fun onScroll(now: Long) {
        lastInteractionTime = now
    }

    fun onScrollStateChanged(isScrolling: Boolean, now: Long) {
        this.isScrolling = isScrolling
        lastInteractionTime = now
    }

    fun isUserInteracting(now: Long): Boolean =
        userIsTouching ||
            isScrolling ||
            lastInteractionTime?.let { now - it < GRACE_MS } == true

    private companion object {
        // Time after the last touch/scroll event during which auto-scroll stays
        // paused, so a decelerating or interrupted fling cannot be yanked back.
        const val GRACE_MS = 1500L
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :features:reader:testDebugUnitTest`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ReaderInteractionGate.kt features/reader/src/test/java/ReaderInteractionGateTest.kt
git commit -m "feat(reader): gate TTS follow-scroll behind user interaction"
```

---

### Task 2: Wire the gate into `ReaderActivity`

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ReaderActivity.kt`

**Interfaces:**
- Consumes: `ReaderInteractionGate` from Task 1 (`onTouch`, `onScroll`, `onScrollStateChanged`, `isUserInteracting`, `userIsTouching`).
- Produces: nothing new (internal wiring only).

- [ ] **Step 1: Add the gate field and import**

Add field next to the other scroll-state fields (~line 116-135):

```kotlin
private val interactionGate = ReaderInteractionGate()
```

Add import `android.view.MotionEvent` and `my.noveldokusha.features.reader.ReaderInteractionGate` is same-package (no import needed).

- [ ] **Step 2: Feed touch events from the ListView**

In `onCreate`, right after `viewBind.listView.adapter = viewAdapter.listView` (~line 215), add:

```kotlin
viewBind.listView.setOnTouchListener { _, event ->
    interactionGate.onTouch(
        actionMasked = event.actionMasked,
        pointerCount = event.pointerCount,
        now = SystemClock.elapsedRealtime()
    )
    false
}
```

- [ ] **Step 3: Feed scroll state from the existing scroll listener**

In the `AbsListView.OnScrollListener` (line 474-522):

- In `onScroll`, first line, add:

```kotlin
interactionGate.onScroll(SystemClock.elapsedRealtime())
```

- In `onScrollStateChanged`, before updating `listIsScrolling`, add:

```kotlin
interactionGate.onScrollStateChanged(
    isScrolling = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE,
    now = SystemClock.elapsedRealtime()
)
```

- [ ] **Step 4: Guard the optional follow-scroll**

Refactor `scrollToReadingPositionOptional` (lines 611-702):

1. Extract the rebind block (lines 623-631) into a private helper:

```kotlin
private fun updateTtsItemRebind(
    chapterIndex: Int,
    chapterItemPosition: Int,
    playState: Utterance.PlayState,
) {
    if (chapterIndex != lastReboundChapterIndex ||
        chapterItemPosition != lastReboundChapterItemPosition ||
        playState != lastReboundPlayState
    ) {
        lastReboundChapterIndex = chapterIndex
        lastReboundChapterItemPosition = chapterItemPosition
        lastReboundPlayState = playState
        viewAdapter.listView.notifyDataSetChanged()
    }
}
```

2. Extract the "visible check + scroll" body (lines 648-701) into:

```kotlin
private fun scrollToReadingPosition(chapterIndex: Int, chapterItemPosition: Int) {
    val firstIndex = viewBind.listView.firstVisiblePosition
    val lastIndex = viewBind.listView.lastVisiblePosition

    for (index in firstIndex..lastIndex) {
        val item = viewAdapter.listView.getItem(index)
        if (
            item.chapterIndex == chapterIndex &&
            item is ReaderItem.Position &&
            item.chapterItemPosition == chapterItemPosition
        ) {
            val viewIndex = index - viewBind.listView.firstVisiblePosition
            val currentOffsetPx =
                viewBind.listView.getChildAt(viewIndex).run { top - paddingTop }
            val newOffsetPx = 200.dpToPx(this@ReaderActivity)

            if (currentOffsetPx > newOffsetPx) {
                viewBind.listView.smoothScrollToPositionFromTop(index, newOffsetPx, 400)
            }
            return
        }
    }

    val itemIndex = indexOfReaderItem(
        list = viewModel.items,
        chapterIndex = chapterIndex,
        chapterItemPosition = chapterItemPosition
    )
    if (itemIndex == -1) return

    val itemPosition = viewAdapter.listView.fromIndexToPosition(itemIndex)
    val newOffsetPx = 200.dpToPx(this@ReaderActivity)

    val distanceBelow = itemPosition - lastIndex
    val distanceAbove = firstIndex - itemPosition
    val threshold = 5

    when {
        distanceBelow in 1..threshold -> {
            viewBind.listView.smoothScrollToPositionFromTop(itemPosition, newOffsetPx, 400)
        }
        distanceAbove in 1..threshold -> {
            viewBind.listView.smoothScrollToPositionFromTop(itemPosition, newOffsetPx, 400)
        }
        else -> {
            viewBind.listView.setSelectionFromTop(itemPosition, newOffsetPx)
        }
    }
}
```

3. Rewrite `scrollToReadingPositionOptional` to be:

```kotlin
private fun scrollToReadingPositionOptional(
    chapterIndex: Int,
    chapterItemPosition: Int,
    playState: Utterance.PlayState,
) {
    // Highlight rebind keeps running while the user interacts.
    updateTtsItemRebind(
        chapterIndex = chapterIndex,
        chapterItemPosition = chapterItemPosition,
        playState = playState,
    )

    // If user is scrolling, don't auto-scroll.
    if (listIsScrolling) {
        // Fling мог быть прерван (шторм notifyDataSetChanged/подгрузка главы) без
        // финального IDLE — гейт «залипает» и follow-скролл молча отключается.
        // Сбрасываем только прерванный fling: при TOUCH_SCROLL палец может лежать
        // неподвижно дольше порога, и сброс дёргал бы список под ним.
        if (lastScrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING &&
            SystemClock.elapsedRealtime() - lastScrollEventTime > 500L
        ) {
            listIsScrolling = false
        } else {
            return
        }
    }

    // The gate also covers finger-down-without-movement and the grace period
    // after a gesture, so auto-scroll never fights a manual interaction.
    if (interactionGate.isUserInteracting(SystemClock.elapsedRealtime())) {
        return
    }

    scrollToReadingPosition(chapterIndex, chapterItemPosition)
}
```

- [ ] **Step 5: Make the IDLE catch-up bypass the gate**

In `onScrollStateChanged`, the catch-up block (lines 512-519) must re-center on manual-interaction end even though the grace period is still active. Replace it with:

```kotlin
if (viewModel.readerSpeaker.isSpeaking.value) {
    val playing = viewModel.readerSpeaker.currentTextPlaying.value
    updateTtsItemRebind(
        chapterIndex = playing.itemPos.chapterIndex,
        chapterItemPosition = playing.itemPos.chapterItemPosition,
        playState = playing.playState,
    )
    scrollToReadingPosition(
        chapterIndex = playing.itemPos.chapterIndex,
        chapterItemPosition = playing.itemPos.chapterItemPosition,
    )
}
```

- [ ] **Step 6: Reset touch state on pause**

In `onPause()`, first line, add:

```kotlin
interactionGate.onTouch(
    actionMasked = MotionEvent.ACTION_CANCEL,
    pointerCount = 1,
    now = SystemClock.elapsedRealtime()
)
```

- [ ] **Step 7: Verify no dead code**

Run a text search for `scrollToReadingPositionOptional` — it must still be called only by the `currentReaderItem` observer (~line 303). `updateTtsItemRebind` and `scrollToReadingPosition` must be used by the optional path and the catch-up. `listIsScrolling`, `lastScrollState`, `lastScrollEventTime` must still be referenced (watchdog + `onScroll`).

- [ ] **Step 8: Run reader tests**

Run: `./gradlew :features:reader:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ReaderActivity.kt
git commit -m "fix(reader): suppress TTS auto-scroll while user touches or scrolls"
```

---

### Task 3: Full verification

**Files:**
- None (verification only).

- [ ] **Step 1: Run unit tests for the whole project**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 2: Compile the debug APK locally (if SDK available) or rely on CI**

If a local Android SDK exists, run: `./gradlew assembleDebug`.
If not, skip — GitHub Actions is the build authority for this repo.

- [ ] **Step 3: Push branch and open PR**

```bash
git push -u origin fix/reader-auto-scroll-respects-manual-scroll
```

Open a PR against `default` with the existing `buildRelease.yml` build note (manual `workflow_dispatch` with `build_type: test`).

- [ ] **Step 4: Trigger the existing test build in GitHub Actions**

Trigger `buildRelease.yml` with `build_type: test` and monitor until the APK build passes. Fix any build errors in a follow-up commit and re-trigger until green.

- [ ] **Step 5: Final review**

Review the PR diff (`git diff origin/default...HEAD`) for scope: only the gate class, `ReaderActivity.kt`, the test file, and the design doc. No workflow changes.
