# Glow Mode Toggle for Floating TTS Menu

**Date:** 2026-07-22
**Status:** Approved design, pending implementation

## Problem

The floating TTS mini player has an auto-glow border around the paragraph text that only appears during playback. Users want control over this glow with three modes: auto (current behavior), always on, and always off.

## Design

### Preference

A new `FLOATING_TTS_GLOW_MODE` string preference in `AppPreferences.kt` with default value `"auto"`. Valid values: `"auto"`, `"on"`, `"off"`.

### State Flow

```
AppPreferences.FLOATING_TTS_GLOW_MODE (persisted)
    ↓
FloatingTtsService.glowMode (mutable state, loaded at startup, saved on change)
    ↓
FloatingTtsOverlayContent (pass-through param)
    ↓
TtsMiniPlayer → FloatingTtsMiniPlayer
    ↓
Glow logic: replaces `val autoGlow = state.isPlaying.value && hasParagraphText`
    with:
    val showGlow = when (glowMode) {
        "on" -> true
        "off" -> false
        else -> state.isPlaying.value && hasParagraphText  // "auto"
    }
    used in the `.then(if (showGlow) ...)` modifier chain
```

### UI — Glow Toggle

In the floating TTS settings section (shown when Tune icon is toggled), after the width slider and before the paragraph mode row, add a new row:

```
Glow    [ Auto ] [ On ] [ Off ]
```

A segmented button group — three small `Surface` composables with rounded borders placed next to each other. The active mode has a filled background (primary color), the inactive modes have an outlined border with surface variant background.

Follows the same row layout pattern as opacity/width/paragraph:
- Label on the left ("Glow")
- Three pill segments on the right

### UI — Paragraph Mode Conversion

Convert the existing paragraph mode selector from text-with-"/" to the same segmented button style:

```
Paragraph    [ TTS ] [ Both ] [ Inverse ]
```

Same visual treatment: active segment filled, inactive segments outlined, rounded corners. The three text segments sit next to each other in a row with no separator character.

### Files Changed

| File | Change |
|---|---|
| `core/.../AppPreferences.kt` | Add `FLOATING_TTS_GLOW_MODE` preference (string, default `"auto"`) |
| `features/reader/.../FloatingTtsService.kt` | Add `glowMode` state, load/save to preferences |
| `features/reader/.../FloatingTtsOverlay.kt` | Pass `glowMode`/`onGlowModeChange` through to TtsMiniPlayer |
| `features/reader/.../TtsMiniPlayer.kt` | Add glow selector UI, update glow logic; convert paragraph mode selector to segmented buttons |
