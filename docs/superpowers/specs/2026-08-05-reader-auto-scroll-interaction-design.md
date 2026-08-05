# Reader auto-scroll must respect manual touch/scroll

Date: 2026-08-05

## Problem

While TTS is active, the reader sometimes forcibly jumps back to the currently
spoken paragraph even while the user is manually scrolling or touching the
screen to look ahead.

## Goals

1. When the user is not interacting, TTS follow-scroll may keep the current
   paragraph visible (unchanged behavior).
2. While the user is touching the screen or manually scrolling, forced
   auto-scroll must pause and must not yank the view back to the current
   paragraph.
3. The current paragraph is re-centered only via the explicit Focus button or
   when the manual interaction ends.
4. TTS playback and paragraph/word highlighting are unchanged.
5. No unrelated features are changed. The fix is small, stable and bug-free.

## Root cause

The follow-scroll guard in `ReaderActivity.scrollToReadingPositionOptional`
relies solely on `listIsScrolling` (the `AbsListView` scroll state). This has
two holes:

- A finger down but not yet moving leaves the scroll state `IDLE`, so
  `listIsScrolling == false` and auto-scroll fires under the finger.
- A decelerating or interrupted fling can have no `onScroll` events for > 500ms,
  so the stale-fling watchdog clears `listIsScrolling` and auto-scroll yanks the
  list while it is still settling.

## Approach

Add an explicit user-interaction gate that combines three signals:

- finger currently touching the list (via `OnTouchListener`),
- list currently scrolling (`listIsScrolling`, unchanged),
- a short grace period after the last touch/scroll event.

The grace period closes the decelerating-fling hole left by the watchdog.

## Design

### New class: `ReaderInteractionGate`

`features/reader/src/main/java/my/noveldokusha/features/reader/ReaderInteractionGate.kt`

A small, pure, unit-testable state holder:

- `fun onTouch(actionMasked: Int, pointerCount: Int, now: Long)`
  - `ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE` -> `userIsTouching = true`
  - `ACTION_UP`, `ACTION_CANCEL` -> `userIsTouching = false`
  - `ACTION_POINTER_UP` -> keep `true` while another pointer remains
  - records `lastInteractionTime = now`
- `fun onScroll(now: Long)` -> `lastInteractionTime = now`
- `fun onScrollStateChanged(isScrolling: Boolean, now: Long)`
  -> sets `isScrolling`, records `lastInteractionTime = now`
- `fun isUserInteracting(now: Long): Boolean`
  -> `userIsTouching || isScrolling || now - lastInteractionTime < GRACE_MS`
- `GRACE_MS = 1500L`

### Wiring in `ReaderActivity`

1. Hold a `ReaderInteractionGate` instance.
2. `viewBind.listView.setOnTouchListener { _, event -> gate.onTouch(...); false }`
   (returning `false` keeps ListView scrolling/selection behavior intact).
3. Feed `onScroll` / `onScrollStateChanged` from the existing
   `AbsListView.OnScrollListener`.
4. `scrollToReadingPositionOptional`:
   - keep the highlight rebind where it is (highlighting still updates while the
     user scrolls),
   - keep the existing stale-fling watchdog,
   - then `if (gate.isUserInteracting(now)) return` instead of only checking
     `listIsScrolling`.
5. The IDLE catch-up re-center (on manual interaction end) calls the scroll core
   directly, bypassing the gate — that is the "manual interaction ends" case.
6. Reset the gate touch state in `onPause` in case `ACTION_CANCEL` was missed.

### Unchanged

- `scrollToReadingPositionForced` (Focus button, TTS prev/next, chapter top)
  stays forced — these are explicit user actions.
- `ttsScrolledToTheTop` / `ttsScrolledToTheBottom` boundary scrolls.
- `onResume` scroll back to the playing paragraph.
- TTS playback, word-level highlighting, adapter rendering.

## Behavior

- Idle: follow-scroll keeps the current paragraph visible (as today).
- Touching or scrolling: auto-scroll is paused; no snap-back.
- Just after a gesture / decelerating fling: auto-scroll stays paused for the
  1.5s grace period.
- Focus button: forced re-center (as today).
- Manual interaction ends (IDLE): immediate re-center if the paragraph is
  off-screen (as today).

## Edge cases

- Finger held still without moving: `userIsTouching` stays true -> blocked.
- Tap to toggle reader info: blocked during the tap and the grace period.
- Multi-touch: gate only clears `userIsTouching` when no pointer remains.
- Missed `ACTION_CANCEL` (activity paused mid-touch): reset in `onPause`.
- Programmatic follow-scroll events update `lastInteractionTime`; this does not
  create a self-suppression loop because TTS paragraph transitions are seconds
  apart and the grace period is short.

## Verification

- JUnit test `ReaderInteractionGateTest` covering touch, scroll, grace-period
  and multi-pointer transitions.
- GitHub Actions build via the existing `.github/workflows/buildRelease.yml`
  triggered with `build_type: test` (signing secrets are already configured in
  the repo). No workflow files are changed.
- The build must pass with no build or runtime errors.
