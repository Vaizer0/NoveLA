# Glow Mode Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 3-mode glow toggle (auto/on/off) to floating TTS settings and convert paragraph mode selector to segmented buttons.

**Architecture:** Add string preference + mutable state for glowMode; add segmented button composable; update glow logic; convert paragraph mode from text-with-"/" to same segmented button style.

**Tech Stack:** Kotlin, Jetpack Compose, Material3

---

### Task 1: Add preference + service state + overlay plumbing

**Files:**
- Modify: `core/.../appPreferences/AppPreferences.kt` — add `FLOATING_TTS_GLOW_MODE`
- Modify: `features/reader/.../services/FloatingTtsService.kt` — load/save glowMode

- [ ] Add preference in `AppPreferences.kt` after `FLOATING_TTS_PARAGRAPH_MODE`:
```kotlin
    val FLOATING_TTS_GLOW_MODE = object : Preference<String>("FLOATING_TTS_GLOW_MODE") {
        override var value by SharedPreference_String(name, preferences, "auto")
    }
```

- [ ] In `FloatingTtsService.kt` companion, add `var glowMode by mutableStateOf("auto")` next to `paragraphMode`
- [ ] Set `glowMode = appPreferences.FLOATING_TTS_GLOW_MODE.value` alongside `paragraphMode` load
- [ ] Pass `glowMode = glowMode, onGlowModeChange = { glowMode = it; appPreferences.FLOATING_TTS_GLOW_MODE.value = it }` alongside `paragraphMode`/`onParagraphModeChange` params
- [ ] Commit

### Task 2: Add params to FloatingTtsOverlay + TtsMiniPlayer wrappers

**Files:**
- Modify: `FloatingTtsOverlay.kt`
- Modify: `TtsMiniPlayer.kt` (TtsMiniPlayer wrapper only)

- [ ] In `FloatingTtsOverlayContent`: add `glowMode: String = "auto"` and `onGlowModeChange: ((String) -> Unit)? = null` params after `onParagraphModeChange`; pass through to `TtsMiniPlayer`
- [ ] In `TtsMiniPlayer` wrapper: add `glowMode: String = "auto"` and `onGlowModeChange: ((String) -> Unit)? = null` params; pass through to `FloatingTtsMiniPlayer`
- [ ] Commit

### Task 3: Segmented button composable + glow logic + paragraph conversion

**Files:**
- Modify: `TtsMiniPlayer.kt` (FloatingTtsMiniPlayer)

- [ ] Add `SegmentedButtonGroup` composable at file level (reusable for both glow and paragraph):
```kotlin
@Composable
private fun SegmentedButtonGroup(
    options: List<String>,
    selected: String,
    onSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            Surface(
                shape = when (index) {
                    0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                    options.lastIndex -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                    else -> RoundedCornerShape(0.dp)
                },
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .clickable { onSelection(option) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

Add imports: `BorderStroke`, `RoundedCornerShape` (already imported).

- [ ] Add `glowMode` and `onGlowModeChange` params to `FloatingTtsMiniPlayer`
- [ ] Replace `val autoGlow = state.isPlaying.value && hasParagraphText` with:
```kotlin
    val showGlow = when (glowMode) {
        "on" -> true
        "off" -> false
        else -> state.isPlaying.value && hasParagraphText
    }
```
- [ ] Update the usage: `if (autoGlow)` → `if (showGlow)`

- [ ] In the settings section, after the width row and before the paragraph mode row, add glow selector:
```kotlin
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = "Glow",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                SegmentedButtonGroup(
                                    options = listOf("Auto", "On", "Off"),
                                    selected = glowMode.replaceFirstChar { it.uppercase() },
                                    onSelection = { onGlowModeChange?.invoke(it.lowercase()) },
                                )
                            }
```

Add import for `replaceFirstChar` if needed (Kotlin stdlib).

- [ ] Convert the paragraph mode selector from text-with-"/" to SegmentedButtonGroup:
```kotlin
                            if (onParagraphModeChange != null && state.parallelEnabled.value) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.tts_floating_paragraph),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    SegmentedButtonGroup(
                                        options = listOf(
                                            stringResource(R.string.tts_voice),
                                            stringResource(R.string.tts_both),
                                            stringResource(R.string.inverse),
                                        ),
                                        selected = when (paragraphMode) {
                                            "tts" -> stringResource(R.string.tts_voice)
                                            "both" -> stringResource(R.string.tts_both)
                                            "inverse" -> stringResource(R.string.inverse)
                                            else -> stringResource(R.string.tts_voice)
                                        },
                                        onSelection = { selected ->
                                            val newMode = when (selected) {
                                                stringResource(R.string.tts_both) -> "both"
                                                stringResource(R.string.inverse) -> "inverse"
                                                else -> "tts"
                                            }
                                            onParagraphModeChange?.invoke(newMode)
                                        },
                                    )
                                }
                            }
```

- [ ] Add `Spacer(Modifier.weight(1f))` import if not present (already there from `Spacer` usage)
- [ ] Remove unused `clickable` imports if no longer needed for paragraph mode... actually `clickable` is used elsewhere in the file
- [ ] Commit

### Task 4: Build APK

- [ ] Push branch to GitHub
- [ ] Run build workflow: `gh workflow run buildRelease.yml --repo Vaizer0/NoveLA --ref feature/tts-menu-reorg -f build_type=test`
- [ ] Verify build passes
