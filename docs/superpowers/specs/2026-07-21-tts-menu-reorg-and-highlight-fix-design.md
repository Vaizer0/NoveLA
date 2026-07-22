# TTS Menu Reorganization & Paragraph Highlight Fix

## Overview

Two changes to the NoveLA TTS UI:

1. Reorganize the TTS settings dialog's action buttons into horizontally scrollable rows
2. Make the floating TTS menu's paragraph border glow follow TTS playback position automatically (removing manual toggle delay)

## Files Modified

| File | Change |
|------|--------|
| `VoiceReaderSettingDialog.kt` | Reorganize button layout |
| `TtsMiniPlayer.kt` | Auto-glow, simplified gesture handling, param removal |
| `FloatingTtsOverlay.kt` | Remove `glowEnabled` / `onToggleGlow` params |
| `FloatingTtsService.kt` | Remove static `glowEnabled` state |

## Change 1: TTS Settings Dialog Button Layout

### Current Layout
```
ElevatedCard:
  - Pitch slider
  - Speed slider
  - FlowRow: [Start Here] [Focus] [Voices] [Original Voice*] [Saved Voices]
  - Surface section:
      Row: "Floating TTS" [Switch]
      Divider
      Row: "Show outside app" [Switch]
  - Playback controls row
```

### New Layout
```
ElevatedCard:
  - Pitch slider
  - Speed slider
  - Row 1 (horizontalScroll): [Start Here] [Focus] [Voices] [Saved Voices] [Original Voice*]
  - Row 2: [Floating TTS toggle chip] [Show outside app toggle chip]
  - Playback controls row
```

### Details
- **Row 1**: Same `AssistChip` styling (heightIn min=30dp, leading icon 14dp, 6dp spacing). Wrapped in `Modifier.horizontalScroll()` inside a `Row` to keep single-line horizontal layout. `Original Voice` shown only when `parallelEnabled == true`.
- **Row 2**: Two `FilterChip` toggle buttons with selected/unselected visual states, replacing the old `Surface`/`Switch` section.

## Change 2: Paragraph Glow Follows TTS Playback

### Current Behavior
- `glowEnabled` is a static `mutableStateOf(false)` in `FloatingTtsService`
- Toggled by ~1200ms long-press on the paragraph text area in the floating mini player
- Border rendered conditionally when `glowEnabled == true`

### New Behavior
- Remove `glowEnabled` state entirely
- Auto-compute: `autoGlow = state.isPlaying.value && hasParagraphText`
- When TTS is playing a visible paragraph → border glow turns on instantly
- When TTS stops or paragraph changes → border glow turns off instantly
- Border color uses existing `ttsHighlightColor` from preferences
- Simplified gesture handler on paragraph surface (remove 1200ms long-press for glow; keep pinch-to-zoom and double-tap for menu collapse)
