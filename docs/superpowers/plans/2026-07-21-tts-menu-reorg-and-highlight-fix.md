# TTS Menu Reorganization & Paragraph Highlight Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Reorganize the TTS settings dialog action buttons into horizontally scrollable rows and make the floating TTS paragraph glow follow playback automatically.

**Architecture:** Two independent changes to the reader module: (1) reorganize `VoiceReaderSettingDialog.kt` button layout from FlowRow to two horizontal rows; (2) remove manual `glowEnabled` toggle in `FloatingTtsService.kt`/`TtsMiniPlayer.kt` and compute auto-glow from `isPlaying && hasParagraphText`.

**Tech Stack:** Kotlin, Jetpack Compose, Android, Gradle

## Global Constraints

- Follow existing code style (AssistChip, colors, spacing patterns)
- Keep chip size and spacing same as original (heightIn min=30dp, leading icon 14dp, 6dp spacing)
- Original Voice chip shown only when `parallelEnabled?.value == true`
- Use `Modifier.horizontalScroll()` for the first row to handle overflow
- Use `FilterChip` for toggle buttons in the second row

---

### Task 1: Create feature branch & read base state

**Files:** None (git operations)

**Interfaces:** N/A

- [ ] **Create feature branch from default branch**

```bash
cd /data/data/com.termux/files/usr/tmp/NoveLA
git checkout -b feature/tts-menu-reorg
```

- [ ] **Verify all files are in their original state**

```bash
git status
ls -la features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt
ls -la features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt
ls -la features/reader/src/main/java/my/noveldokusha/features/reader/ui/FloatingTtsOverlay.kt
ls -la features/reader/src/main/java/my/noveldokusha/features/reader/services/FloatingTtsService.kt
```

---

### Task 2: Reorganize button rows in VoiceReaderSettingDialog.kt

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt:161-298`

**Interfaces:**
- Consumes: Same composable signature — `VoiceReaderSettingDialog(state, floatingTtsState, parallelEnabled)`
- Produces: Two rows replacing the old FlowRow + floating section

- [ ] **Replace the FlowRow (lines 161-252) with a horizontally scrollable Row of action chips**

Replace:
```kotlin
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AssistChip(
                        label = { Text(text = stringResource(id = R.string.start_here)) },
                        onClick = debouncedAction { state.playFirstVisibleItem() },
                        leadingIcon = { Icon(Icons.Filled.CenterFocusWeak, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    AssistChip(
                        label = { Text(text = stringResource(id = R.string.focus)) },
                        onClick = debouncedAction { state.scrollToActiveItem() },
                        leadingIcon = { Icon(Icons.Filled.CenterFocusStrong, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    AssistChip(
                        label = { Text(text = stringResource(id = R.string.voices)) },
                        onClick = { openVoicesDialog = !openVoicesDialog },
                        leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    if (parallelEnabled?.value == true) {
                        AssistChip(
                            label = { Text(text = stringResource(R.string.original_voice)) },
                            onClick = { openOriginalVoiceDialog = !openOriginalVoiceDialog },
                            leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(14.dp)) },
                            modifier = Modifier.heightIn(min = 30.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    AssistChip(
                        label = { Text(text = stringResource(R.string.saved_voices)) },
                        onClick = {
                            dropdownCustomSavedVoicesExpanded.let {
                                it.value = !it.value
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Bookmarks, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Box {
                        DropdownCustomSavedVoices(
                            expanded = dropdownCustomSavedVoicesExpanded,
                            ...
                        )
                        VoiceSelectorDialog(
                            ...
                        )
                    }
                }
```

With:
```kotlin
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    AssistChip(
                        label = { Text(text = stringResource(id = R.string.start_here)) },
                        onClick = debouncedAction { state.playFirstVisibleItem() },
                        leadingIcon = { Icon(Icons.Filled.CenterFocusWeak, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    AssistChip(
                        label = { Text(text = stringResource(id = R.string.focus)) },
                        onClick = debouncedAction { state.scrollToActiveItem() },
                        leadingIcon = { Icon(Icons.Filled.CenterFocusStrong, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    AssistChip(
                        label = { Text(text = stringResource(id = R.string.voices)) },
                        onClick = { openVoicesDialog = !openVoicesDialog },
                        leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    if (parallelEnabled?.value == true) {
                        AssistChip(
                            label = { Text(text = stringResource(R.string.original_voice)) },
                            onClick = { openOriginalVoiceDialog = !openOriginalVoiceDialog },
                            leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(14.dp)) },
                            modifier = Modifier.heightIn(min = 30.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    AssistChip(
                        label = { Text(text = stringResource(R.string.saved_voices)) },
                        onClick = {
                            dropdownCustomSavedVoicesExpanded.let {
                                it.value = !it.value
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Bookmarks, null, Modifier.size(14.dp)) },
                        modifier = Modifier.heightIn(min = 30.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                // Keep the Box with dropdowns OUTSIDE the Row so they overlay properly
                Box {
                    DropdownCustomSavedVoices(
                        expanded = dropdownCustomSavedVoicesExpanded,
                        list = state.customSavedVoices.value,
                        currentVoice = state.activeVoice.value,
                        currentVoiceSpeed = state.voiceSpeed.value,
                        currentVoicePitch = state.voicePitch.value,
                        onPredefinedSelected = {
                            state.setVoiceSpeed(it.speed)
                            state.setVoicePitch(it.pitch)
                            state.setVoiceId(it.voiceId)
                        },
                        setCustomSavedVoices = state.setCustomSavedVoices
                    )
                    VoiceSelectorDialog(
                        availableVoices = state.availableVoices,
                        currentVoice = state.activeVoice.value,
                        inputTextFilter = rememberSaveable { mutableStateOf("") },
                        setVoice = state.setVoiceId,
                        isDialogOpen = openVoicesDialog,
                        setDialogOpen = { openVoicesDialog = it }
                    )
                    VoiceSelectorDialog(
                        availableVoices = state.availableVoices,
                        currentVoice = state.availableVoices.find { it.id == state.originalVoiceId.value },
                        inputTextFilter = rememberSaveable { mutableStateOf("") },
                        setVoice = { state.setOriginalVoiceId(it) },
                        isDialogOpen = openOriginalVoiceDialog,
                        setDialogOpen = { openOriginalVoiceDialog = it }
                    )
                }
```

Add import for `rememberScrollState`:
```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
```

- [ ] **Replace the Floating TTS Surface section (lines 254-298) with a Row of FilterChip toggles**

Replace the old Surface section (lines 254-298):
```kotlin
                if (floatingTtsState != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.tts_floating),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Switch(
                                    checked = floatingTtsState.isEnabled.value,
                                    onCheckedChange = { floatingTtsState.isEnabled.value = it }
                                )
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.tts_floating_show_outside_app),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = floatingTtsState.showOutsideApp.value,
                                    onCheckedChange = { floatingTtsState.showOutsideApp.value = it },
                                    enabled = floatingTtsState.isEnabled.value
                                )
                            }
                        }
                    }
                }
```

With:
```kotlin
                if (floatingTtsState != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterChip(
                            selected = floatingTtsState.isEnabled.value,
                            onClick = { floatingTtsState.isEnabled.value = !floatingTtsState.isEnabled.value },
                            label = { Text(text = stringResource(R.string.tts_floating)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.RecordVoiceOver,
                                    null,
                                    Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 30.dp),
                        )
                        FilterChip(
                            selected = floatingTtsState.showOutsideApp.value,
                            onClick = { floatingTtsState.showOutsideApp.value = !floatingTtsState.showOutsideApp.value },
                            label = { Text(text = stringResource(R.string.tts_floating_show_outside_app)) },
                            enabled = floatingTtsState.isEnabled.value,
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Bookmarks,
                                    null,
                                    Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 30.dp),
                        )
                    }
                }
```

Add import for FilterChip:
```kotlin
import androidx.compose.material3.FilterChip
```

- [ ] **Remove unused imports** — `HorizontalDivider` may no longer be needed if removed from this file; also `AnimatedContent`, `animateFloatAsState`, `alpha`, `Spacer`, `width`, `clickable`, `height`, `FlowRow` if no longer used elsewhere.

- [ ] **Verify the file compiles conceptually**

Run: `cd /data/data/com.termux/files/usr/tmp/NoveLA && ./gradlew :features:reader:compileDebugKotlin 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt
git commit -m "refactor(tts): reorganize TTS settings dialog into two horizontal rows"
```

---

### Task 3: Remove glowEnabled state from FloatingTtsService.kt

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/services/FloatingTtsService.kt:62,199,403-407`

**Interfaces:**
- Consumes: N/A — removes `glowEnabled` static state
- Produces: No `glowEnabled` and `onToggleGlow` param in `FloatingTtsOverlayContent()` call

- [ ] **Remove `glowEnabled` static state declaration**

Delete line 62:
```kotlin
        var glowEnabled = mutableStateOf(false)
```

- [ ] **Remove `glowEnabled` save/load in `loadSavedState()`**

Delete line 199:
```kotlin
        glowEnabled.value = appPreferences.FLOATING_TTS_GLOW_ENABLED.value
```

- [ ] **Remove `glowEnabled` and `onToggleGlow` params from the `FloatingTtsOverlayContent` call**

In the `setContent {}` block, remove:
```kotlin
                        glowEnabled = glowEnabled.value,
                        onToggleGlow = {
                            glowEnabled.value = !glowEnabled.value
                            appPreferences.FLOATING_TTS_GLOW_ENABLED.value = glowEnabled.value
                        },
```

- [ ] **Verify file compiles**

Run: `cd /data/data/com.termux/files/usr/tmp/NoveLA && ./gradlew :features:reader:compileDebugKotlin 2>&1 | tail -20`
Expected: Compilation errors expected (callers still reference old params)

- [ ] **Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/services/FloatingTtsService.kt
git commit -m "refactor(tts): remove glowEnabled static state from FloatingTtsService"
```

---

### Task 4: Remove glow params from FloatingTtsOverlay.kt

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/FloatingTtsOverlay.kt`

**Interfaces:**
- Consumes: Removes `glowEnabled` and `onToggleGlow` parameters
- Produces: Cleaner `FloatingTtsOverlayContent` composable

- [ ] **Remove `glowEnabled` and `onToggleGlow` params from `FloatingTtsOverlayContent`**

Delete:
```kotlin
    glowEnabled: Boolean = false,
    onToggleGlow: (() -> Unit)? = null,
```
And remove the passing of these params to `TtsMiniPlayer`:
```kotlin
            glowEnabled = glowEnabled,
            onToggleGlow = onToggleGlow,
```

- [ ] **Verify file compiles**

Run: `cd /data/data/com.termux/files/usr/tmp/NoveLA && ./gradlew :features:reader:compileDebugKotlin 2>&1 | tail -20`
Expected: Compilation errors expected (callers in FloatingTtsService still reference old params)

- [ ] **Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/FloatingTtsOverlay.kt
git commit -m "refactor(tts): remove glow params from FloatingTtsOverlayContent"
```

---

### Task 5: Implement auto-glow & simplify gestures in TtsMiniPlayer.kt

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt`

**Interfaces:**
- Consumes: `state.isPlaying`, `hasParagraphText` — existing fields
- Produces: Auto-glow border, simplified gesture handler

- [ ] **Remove `glowEnabled` and `onToggleGlow` params from `TtsMiniPlayer` composable**

Delete:
```kotlin
    glowEnabled: Boolean = false,
    onToggleGlow: (() -> Unit)? = null,
```
And remove their passing to `FloatingTtsMiniPlayer`.

- [ ] **Remove `glowEnabled` and `onToggleGlow` params from `FloatingTtsMiniPlayer`**

Delete the params and their passing to children.

- [ ] **Compute auto-glow instead of using glowEnabled parameter**

In `FloatingTtsMiniPlayer`, after `hasParagraphText` (line 345), add:
```kotlin
    val autoGlow = state.isPlaying.value && hasParagraphText
```

Replace the glow border condition (lines 565-572):
```kotlin
                        .then(
                            if (glowEnabled) {
                                Modifier
                                    .border(1.5.dp, glowColor, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
```
With:
```kotlin
                        .then(
                            if (autoGlow) {
                                Modifier
                                    .border(1.5.dp, glowColor, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
```

- [ ] **Simplify the gesture handler to remove glow toggle logic**

Replace the entire `pointerInput(Unit)` block (lines 573-636) with simplified version:
```kotlin
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                var isPinch = false
                                var wasDrag = false

                                val released = withTimeoutOrNull(1200L) {
                                    var firstPos: Offset? = null
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            isPinch = true
                                            break
                                        }
                                        if (pressed.isEmpty()) break

                                        if (firstPos == null) {
                                            firstPos = pressed.first().position
                                        } else if ((pressed.first().position - firstPos!!).getDistance() > 20f) {
                                            wasDrag = true
                                            break
                                        }
                                    }
                                    true
                                }

                                if (isPinch) {
                                    var prevSpan = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            val p1 = pressed[0].position
                                            val p2 = pressed[1].position
                                            val span = (p1 - p2).getDistance()
                                            if (prevSpan > 0f) {
                                                val zoom = span / prevSpan
                                                val newWidth = (currentPanelWidth * zoom).coerceIn(minWidth, maxWidth)
                                                onPanelWidthChange?.invoke(newWidth)
                                            }
                                            prevSpan = span
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } else if (released != null && !wasDrag) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTime.longValue < 350) {
                                        onToggleMenuHidden?.invoke()
                                        lastTapTime.longValue = 0L
                                    } else {
                                        lastTapTime.longValue = now
                                    }
                                } else {
                                    do {
                                        val event = awaitPointerEvent()
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                        }
```

Key changes: removed the `released == null && !wasDrag` branch (which was the glow toggle), so on any simple tap, the double-tap menu toggle logic runs instead.

- [ ] **Verify file compiles**

Run: `cd /data/data/com.termux/files/usr/tmp/NoveLA && ./gradlew :features:reader:compileDebugKotlin 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt
git commit -m "feat(tts): auto-glow follows TTS playback, simplified gesture handler"
```

---

### Task 6: Verify full compilation

**Files:** None

- [ ] **Run full project compile check**

```bash
cd /data/data/com.termux/files/usr/tmp/NoveLA && ./gradlew assembleDebug 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Run lint**

```bash
cd /data/data/com.termux/files/usr/tmp/NoveLA && ./gradlew lint 2>&1 | tail -30
```
Expected: No errors (warnings OK)

---

### Task 7: Push branch and open PR for GitHub build

**Files:** None

**Interfaces:** N/A

- [ ] **Push the branch to GitHub**

```bash
cd /data/data/com.termux/files/usr/tmp/NoveLA
git remote set-url origin https://github.com/Vaizer0/NoveLA
git push -u origin feature/tts-menu-reorg
```

- [ ] **Open a PR for the changes**

```bash
gh pr create \
  --base default \
  --head feature/tts-menu-reorg \
  --title "Reorganize TTS menu buttons & auto-glow on playback" \
  --body "## Changes

1. **TTS settings dialog**: Reorganized action buttons into two horizontal rows:
   - Row 1 (scrollable): Start Here, Focus, Voices, Saved Voices + Original Voice (when available)
   - Row 2: Floating TTS and Show outside app as toggle FilterChips

2. **Floating TTS highlight**: Paragraph border glow now follows TTS playback automatically — no manual toggle needed, no 1200ms delay.

Closes #-"
```

- [ ] **Trigger build workflow**

```bash
gh workflow run buildRelease.yml --ref feature/tts-menu-reorg -f build_type=test
```

- [ ] **Verify workflow passes**

Monitor: `gh run list --workflow=buildRelease.yml --limit 1`
Expected: Status `completed`, conclusion `success`
