# TTS Duration Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an accurate, self-learning TTS chapter duration tracker with a thin progress bar and elapsed/remaining timer in the TTS player UI.

**Architecture:** A new `TTSDurationTracker` class learns per-character-type speaking costs from completed chapters. It predicts chapter duration before playback starts using learned data. A thin progress bar with elapsed/remaining time is added below the TTS play/pause controls. Learning happens at chapter boundaries — not during playback — keeping the system lightweight.

**Tech Stack:** Kotlin, SharedPreferences (via existing `AppPreferences` pattern), Compose UI, existing `TextToSpeechManager` and `ReaderTextToSpeech` infrastructure.

## Global Constraints

- Must not break existing TTS playback or in-app word highlight
- Must be lightweight — no new services, no new notification channels
- Learning happens only at chapter finish (not per-paragraph) to keep it simple
- Progress bar is purely visual — no scrubbing/seeking
- Timer format: `mm:ss` horizontal (not stacked)
- Timer click toggles between elapsed/remaining
- Default: always on (no toggle needed — it's just a better version of the existing estimated time)

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `core/src/main/java/my/noveldokusha/core/appPreferences/AppPreferences.kt` | Modify | Add `TTS_DURATION_MODEL` preference |
| `features/reader/src/main/java/my/noveldokusha/features/reader/features/TTSDurationTracker.kt` | Create | Learning model + prediction |
| `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt` | Modify | Hook chapter start/end, expose tracker state |
| `features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreenState.kt` | Modify | Add duration tracker state to Settings |
| `features/reader/src/main/java/my/noveldokusha/features/reader/ReaderViewModel.kt` | Modify | Wire tracker state |
| `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressBar.kt` | Create | Progress bar + timer composable |
| `features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt` | Modify | Add progress bar below play/pause controls |
| `strings/src/main/res/values/strings.xml` | Modify | Add any new string resources |

---

### Task 1: Create the TTSDurationTracker learning model

**Files:**
- Create: `features/reader/src/main/java/my/noveldokusha/features/reader/features/TTSDurationTracker.kt`

**Interfaces:**
- Consumes: paragraph text (String), chapter texts (List<String>), voice speed (Float)
- Produces: predicted chapter duration (Long ms), elapsed time (Long ms), remaining time (Long ms), progress (Float 0..1)

- [ ] **Step 1: Create the TTSDurationTracker class with character-type cost model**

```kotlin
package my.noveldokusha.features.reader.features

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Self-learning TTS duration predictor.
 *
 * Tracks per-character-type speaking costs (ms per char) learned from
 * completed chapters. Predicts chapter duration before playback starts.
 * Learning happens at chapter boundaries — not during playback.
 */
internal class TTSDurationTracker(
    private val preferences: SharedPreferences,
) {

    @Serializable
    private data class ModelData(
        val costs: MutableMap<String, Double> = mutableMapOf(),
        var sampleCount: Int = 0,
    )

    private var model = loadModel()

    // Chapter tracking state
    private var chapterStartTimeMs: Long = 0L
    private var chapterPauseAccumMs: Long = 0L
    private var chapterPauseStartMs: Long = 0L
    private var isPaused: Boolean = false
    private var currentChapterTexts: List<String> = emptyList()
    private var currentSpeed: Float = 1.0f

    // Prediction cache
    private var cachedChapterDurationMs: Long = 0L
    private var cachedProgress: Float = 0f
    private var cachedElapsedMs: Long = 0L
    private var cachedRemainingMs: Long = 0L

    companion object {
        private const val PREF_KEY = "TTS_DURATION_MODEL"
        private const val LEARNING_RATE = 0.25
        private const val MIN_COST = 15.0   // ms per char minimum
        private const val MAX_COST = 500.0  // ms per char maximum

        // Default costs based on typical TTS research (ms per char at speed=1.0)
        private val DEFAULT_COSTS = mapOf(
            "cjk" to 85.0,
            "latin" to 45.0,
            "digit" to 60.0,
            "space" to 20.0,
            "comma" to 150.0,    // ，、,
            "period" to 250.0,   // 。
            "question" to 300.0, // ？
            "exclamation" to 300.0, // ！
            "ellipsis" to 400.0, // …
            "other_punct" to 200.0,
        )
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Predict total chapter duration in ms before playback starts. */
    fun predictChapterDuration(texts: List<String>, speed: Float): Long {
        currentSpeed = speed
        val totalMs = texts.sumOf { predictParagraphDuration(it, speed) }
        cachedChapterDurationMs = totalMs
        return totalMs
    }

    /** Call when first word of chapter starts playing. */
    fun onChapterStart(texts: List<String>, speed: Float) {
        chapterStartTimeMs = System.currentTimeMillis()
        chapterPauseAccumMs = 0L
        chapterPauseStartMs = 0L
        isPaused = false
        currentChapterTexts = texts
        currentSpeed = speed
    }

    /** Call when TTS pauses. */
    fun onPause() {
        if (!isPaused) {
            isPaused = true
            chapterPauseStartMs = System.currentTimeMillis()
        }
    }

    /** Call when TTS resumes. */
    fun onResume() {
        if (isPaused) {
            chapterPauseAccumMs += System.currentTimeMillis() - chapterPauseStartMs
            isPaused = false
        }
    }

    /** Call when chapter finishes. Learns from actual vs predicted. */
    fun onChapterFinish() {
        if (isPaused) {
            chapterPauseAccumMs += System.currentTimeMillis() - chapterPauseStartMs
            isPaused = false
        }
        val actualDurationMs = System.currentTimeMillis() - chapterStartTimeMs - chapterPauseAccumMs
        if (actualDurationMs < 1000 || currentChapterTexts.isEmpty()) return

        val predictedMs = predictChapterDuration(currentChapterTexts, currentSpeed)
        if (predictedMs < 100) return

        updateModel(currentChapterTexts, actualDurationMs)
        Timber.d("TTSDurationTracker: chapter learned actual=${actualDurationMs}ms predicted=${predictedMs}ms error=${((actualDurationMs - predictedMs) * 100 / predictedMs).toInt()}%")
    }

    /** Get cached progress (0..1) for current chapter. */
    fun getProgress(): Float {
        if (cachedChapterDurationMs <= 0) return 0f
        val elapsed = getElapsedMs()
        cachedProgress = (elapsed.toFloat() / cachedChapterDurationMs).coerceIn(0f, 1f)
        return cachedProgress
    }

    /** Get elapsed time in ms. */
    fun getElapsedMs(): Long {
        if (chapterStartTimeMs == 0L) return 0L
        val now = if (isPaused) chapterPauseStartMs else System.currentTimeMillis()
        return now - chapterStartTimeMs - chapterPauseAccumMs
    }

    /** Get remaining time in ms. */
    fun getRemainingMs(): Long {
        return (cachedChapterDurationMs - getElapsedMs()).coerceAtLeast(0L)
    }

    /** Reset state (call when TTS stops completely). */
    fun reset() {
        chapterStartTimeMs = 0L
        chapterPauseAccumMs = 0L
        chapterPauseStartMs = 0L
        isPaused = false
        currentChapterTexts = emptyList()
        cachedChapterDurationMs = 0L
        cachedProgress = 0f
        cachedElapsedMs = 0L
        cachedRemainingMs = 0L
    }

    // ── Prediction ──────────────────────────────────────────────────────

    private fun predictParagraphDuration(text: String, speed: Float): Long {
        val costs = model.costs
        var totalMs = 0.0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val type = charType(c)
            totalMs += costs[type] ?: (DEFAULT_COSTS[type] ?: 50.0)
            i++
        }
        return (totalMs / speed).toLong()
    }

    private fun charType(c: Char): String = when {
        c.isWhitespace() -> "space"
        c.category == CharCategory.OTHER_LETTER -> "cjk"       // CJK ideographs
        c in '，、' -> "comma"
        c in '。．' -> "period"
        c in '？?' -> "question"
        c in '！!' -> "exclamation"
        c == '…' -> "ellipsis"
        c.isLetter() -> "latin"
        c.isDigit() -> "digit"
        c.category in setOf(
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
        ) -> "other_punct"
        else -> "other_punct"
    }

    // ── Learning ────────────────────────────────────────────────────────

    private fun updateModel(texts: List<String>, actualDurationMs: Long) {
        val fullText = texts.joinToString("")
        if (fullText.isEmpty()) return

        val predictedMs = predictChapterDuration(texts, currentSpeed)
        if (predictedMs <= 0) return

        // Count char types
        val charCounts = mutableMapOf<String, Int>()
        for (c in fullText) {
            val type = charType(c)
            charCounts[type] = (charCounts[type] ?: 0) + 1
        }
        val totalChars = charCounts.values.sum().toDouble()
        if (totalChars <= 0) return

        // Error in ms at base speed (1.0x)
        val errorMs = actualDurationMs - predictedMs

        // Distribute error proportionally across char types
        charCounts.forEach { (type, count) ->
            val weight = count.toDouble() / totalChars
            val currentCost = model.costs[type] ?: (DEFAULT_COSTS[type] ?: 50.0)
            // How much of the total error this type's chars contributed
            val typeErrorShare = errorMs * weight
            // Convert to per-char adjustment
            val perCharAdjustment = typeErrorShare / count
            val newCost = (currentCost + perCharAdjustment * LEARNING_RATE)
                .coerceIn(MIN_COST, MAX_COST)
            model.costs[type] = newCost
        }

        model.sampleCount++
        saveModel()
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun loadModel(): ModelData {
        return try {
            val json = preferences.getString(PREF_KEY, null) ?: return ModelData()
            Json.decodeFromString<ModelData>(json)
        } catch (e: Exception) {
            Timber.w(e, "TTSDurationTracker: failed to load model, using defaults")
            ModelData()
        }
    }

    private fun saveModel() {
        try {
            val json = Json.encodeToString(model)
            preferences.edit().putString(PREF_KEY, json).apply()
        } catch (e: Exception) {
            Timber.w(e, "TTSDurationTracker: failed to save model")
        }
    }
}
```

- [ ] **Step 2: Verify file compiles**

Run: No build available locally — verify syntax manually. The file uses only standard Kotlin, `SharedPreferences`, `Serializable`, and `Timber` — all already in the project dependencies.

- [ ] **Step 3: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/features/TTSDurationTracker.kt
git commit -m "feat(tts): add TTSDurationTracker learning model"
```

---

### Task 2: Add SharedPreferences persistence in AppPreferences

**Files:**
- Modify: `core/src/main/java/my/noveldokusha/core/appPreferences/AppPreferences.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `TTS_DURATION_TRACKER` preference (SharedPreferences instance for the tracker)

- [ ] **Step 1: Add the preference to AppPreferences**

The `TTSDurationTracker` needs access to SharedPreferences. Rather than adding a dedicated preference field (it manages its own JSON blob via a single key), the tracker accepts `SharedPreferences` directly in its constructor. The `ReaderTextToSpeech` class can pass `context.getSharedPreferences("tts_duration_tracker", Context.MODE_PRIVATE)` or simply use the default prefs.

Actually, to keep it bloat-free, just have `TTSDurationTracker` use its own SharedPreferences file via the Application context. No changes needed to `AppPreferences.kt` — the tracker is self-contained.

**No changes needed.** Skip this task.

---

### Task 3: Hook TTSDurationTracker into ReaderTextToSpeech

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt`

**Interfaces:**
- Consumes: `TTSDurationTracker` (from Task 1), `TextToSpeechManager.currentTextSpeakFlow`, `items` list, `voiceSpeed`
- Produces: Exposed `durationTracker` instance for UI, updated `estimatedTotalSeconds` and `estimatedRemainingSeconds` using the tracker

- [ ] **Step 1: Add TTSDurationTracker field and initialization**

In `ReaderTextToSpeech.kt`, add after the `manager` declaration (around line 136):

```kotlin
    private val durationTracker = TTSDurationTracker(
        context.getSharedPreferences("tts_duration_tracker", android.content.Context.MODE_PRIVATE)
    )
```

- [ ] **Step 2: Expose tracker state in TextToSpeechSettingData**

Add to `TextToSpeechSettingData` data class (after `spokenWordRange`):

```kotlin
    val durationProgress: State<Float>,
    val durationElapsedMs: State<Long>,
    val durationRemainingMs: State<Long>,
    val durationTotalMs: State<Long>,
```

Then in the `state = TextToSpeechSettingData(...)` constructor, add:

```kotlin
        durationProgress = derivedStateOf { durationTracker.getProgress() },
        durationElapsedMs = derivedStateOf { durationTracker.getElapsedMs() },
        durationRemainingMs = derivedStateOf { durationTracker.getRemainingMs() },
        durationTotalMs = derivedStateOf { durationTracker.cachedChapterDurationMs },
```

- [ ] **Step 3: Hook chapter start/finish/pause/resume into existing TTS flow**

In the `init` block of `ReaderTextToSpeech`, modify the existing `currentTextSpeakFlow` collection to hook chapter lifecycle:

Replace the existing `coroutineScope.launch` block that collects `currentTextSpeakFlow` (lines 274-342) with:

```kotlin
    init {
        coroutineScope.launch {
            manager.serviceLoadedFlow.take(1).collect {
                manager.trySetVoicePitch(getPreferredVoicePitch())
                manager.trySetVoiceSpeed(getPreferredVoiceSpeed())

                val preferredVoiceId = getPreferredVoiceId()
                val preferredEngine = getPreferredVoiceEngine()
                val defaultEngine = manager.service.defaultEngine ?: ""

                val voiceData = manager.availableVoices.find { it.id == preferredVoiceId }
                val targetEngine = voiceData?.enginePackage ?: preferredEngine

                if (targetEngine.isNotEmpty() && targetEngine != defaultEngine) {
                    manager.reinitWithEngine(
                        enginePackage = targetEngine,
                        voiceId = preferredVoiceId
                    )
                } else {
                    manager.trySetVoiceById(preferredVoiceId)
                }
            }
        }

        manager.init()

        // Duration tracker: learn from completed chapters
        coroutineScope.launch {
            var lastChapterIndex = -1
            manager.currentTextSpeakFlow.collect { utterance ->
                val chapterIndex = utterance.itemPos.chapterIndex

                when (utterance.playState) {
                    Utterance.PlayState.PLAYING -> {
                        // New chapter started — predict duration and begin tracking
                        if (chapterIndex != lastChapterIndex && isChapterIndexValid(chapterIndex)) {
                            val chapterTexts = getChapterTexts(chapterIndex)
                            val speed = manager.voiceSpeed.floatValue
                            durationTracker.predictChapterDuration(chapterTexts, speed)
                            durationTracker.onChapterStart(chapterTexts, speed)
                            lastChapterIndex = chapterIndex
                        }
                    }
                    Utterance.PlayState.FINISHED -> {
                        // Chapter ended — learn from this chapter
                        if (chapterIndex == lastChapterIndex) {
                            durationTracker.onChapterFinish()
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Duration tracker: track pause/resume
        coroutineScope.launch {
            snapshotFlow { state.isPlaying.value }
                .collect { playing ->
                    if (playing) durationTracker.onResume()
                    else durationTracker.onPause()
                }
        }

        // Calibration (existing, kept as-is)
        coroutineScope.launch {
            val paragraphStartTimes = mutableMapOf<String, Long>()
            manager.currentTextSpeakFlow.collect { utterance ->
                // ... existing calibration code unchanged ...
            }
        }
    }
```

- [ ] **Step 4: Add helper to get all paragraph texts for a chapter**

Add to `ReaderTextToSpeech`:

```kotlin
    private fun getChapterTexts(chapterIndex: Int): List<String> {
        return items.filterIsInstance<ReaderItem.Text>()
            .filter { it.chapterIndex == chapterIndex }
            .map { ttsText(it) }
    }
```

- [ ] **Step 5: Update estimatedTotalSeconds and estimatedRemainingSeconds to use tracker**

Replace the existing `estimatedTotalSeconds` and `estimatedRemainingSeconds` derived states:

```kotlin
    val estimatedTotalSeconds = derivedStateOf {
        val trackerMs = durationTracker.cachedChapterDurationMs
        if (trackerMs > 0) (trackerMs / 1000).toInt()
        else {
            // Fallback to old method if no tracker data
            val currentSpeed = manager.voiceSpeed.floatValue
            val cps = baseCharactersPerSecond.value * currentSpeed
            if (cps > 0f) (chapterCharacterCount.value / cps).toInt() else 0
        }
    }

    val estimatedRemainingSeconds = derivedStateOf {
        val trackerMs = durationTracker.getRemainingMs()
        if (trackerMs > 0) (trackerMs / 1000).toInt()
        else {
            val currentSpeed = manager.voiceSpeed.floatValue
            val cps = baseCharactersPerSecond.value * currentSpeed
            if (cps > 0f) (remainingCharacterCount.value / cps).toInt() else 0
        }
    }
```

- [ ] **Step 6: Add reset on full TTS stop**

In the `stop()` method, add:

```kotlin
    fun stop() {
        lifecycleLock.lock()
        try {
            state.isPlaying.value = false
            updateJob?.cancel()
            manager.stop()
            durationTracker.reset()
        } finally {
            lifecycleLock.unlock()
        }
    }
```

- [ ] **Step 7: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt
git commit -m "feat(tts): hook TTSDurationTracker into ReaderTextToSpeech lifecycle"
```

---

### Task 4: Add duration tracker state to ReaderScreenState

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreenState.kt`

**Interfaces:**
- Consumes: `TextToSpeechSettingData` duration fields (from Task 3)
- Produces: Exposed state for UI composables

- [ ] **Step 1: Add duration fields to Settings**

Add to `ReaderScreenState.Settings` data class:

```kotlin
    @Stable
    data class Settings(
        val isTextSelectable: State<Boolean>,
        val keepScreenOn: State<Boolean>,
        val fullScreen: State<Boolean>,
        val isSingleTapToOpenSettings: State<Boolean>,
        val textToSpeech: TextToSpeechSettingData,
        val liveTranslation: LiveTranslationSettingData,
        val style: StyleSettingsData,
        val selectedSetting: MutableState<Type>,
        val floatingTts: FloatingTtsSettingsData,
        val ttsHighlight: TtsHighlightSettingsData,
    ) {
```

No new field needed — the duration data lives inside `TextToSpeechSettingData` which is already accessible via `settings.textToSpeech`. The UI reads `settings.textToSpeech.durationProgress`, etc.

- [ ] **Step 2: Commit (no changes needed)**

Skip — the fields are already in `TextToSpeechSettingData` from Task 3.

---

### Task 5: Create the TtsProgressBar composable

**Files:**
- Create: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressBar.kt`

**Interfaces:**
- Consumes: `progress: Float` (0..1), `elapsedMs: Long`, `remainingMs: Long`, `totalMs: Long`
- Produces: Composable UI element

- [ ] **Step 1: Create the TtsProgressBar composable**

```kotlin
package my.noveldokusha.features.reader.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TtsProgressBar(
    progress: Float,
    elapsedMs: Long,
    remainingMs: Long,
    totalMs: Long,
    modifier: Modifier = Modifier,
) {
    var showRemaining by remember { mutableStateOf(true) }
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "tts_progress")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatDurationShort(elapsedMs),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .weight(1f)
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Text(
            text = if (showRemaining) "-${formatDurationShort(remainingMs)}"
                   else formatDurationShort(totalMs),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { showRemaining = !showRemaining },
        )
    }
}

private fun formatDurationShort(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
```

- [ ] **Step 2: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsProgressBar.kt
git commit -m "feat(tts): add TtsProgressBar composable"
```

---

### Task 6: Add progress bar to VoiceReaderSettingDialog

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt`

**Interfaces:**
- Consumes: `TextToSpeechSettingData.durationProgress`, `durationElapsedMs`, `durationRemainingMs`, `durationTotalMs`
- Produces: Renders TtsProgressBar below the play/pause button row

- [ ] **Step 1: Read the current VoiceReaderSettingDialog structure**

Find the Row that contains the play/pause/prev/next buttons. Add the progress bar directly below that Row.

- [ ] **Step 2: Add TtsProgressBar below the control buttons**

In `VoiceReaderSettingDialog.kt`, after the Row containing play/pause/prev/next buttons (the Row with `Arrangement.Center`), add:

```kotlin
            // ... existing play/pause Row ...

            // Duration progress bar
            if (state.isPlaying.value || state.isThereActiveItem.value) {
                val totalMs by state.durationTotalMs
                if (totalMs > 0) {
                    TtsProgressBar(
                        progress = state.durationProgress.value,
                        elapsedMs = state.durationElapsedMs.value,
                        remainingMs = state.durationRemainingMs.value,
                        totalMs = totalMs,
                    )
                }
            }
```

- [ ] **Step 3: Add import for TtsProgressBar**

At the top of the file:

```kotlin
import my.noveldokusha.features.reader.ui.TtsProgressBar
```

- [ ] **Step 4: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt
git commit -m "feat(tts): add duration progress bar to TTS player UI"
```

---

### Task 7: Wire everything through ReaderViewModel

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ReaderViewModel.kt`

**Interfaces:**
- Consumes: `readerSession.readerTextToSpeech.state` (already contains duration fields)
- Produces: Nothing new — the state is already wired through `readerSession.readerTextToSpeech.state`

- [ ] **Step 1: Verify no changes needed**

The `TextToSpeechSettingData` with duration fields is already passed through `readerSession.readerTextToSpeech.state` (line 68 of ReaderViewModel). The UI reads directly from `settings.textToSpeech.durationProgress`, etc.

**No changes needed.** The existing wiring passes the new fields through automatically.

- [ ] **Step 2: Skip commit**

---

### Task 8: Update preview data in ReaderScreen.kt

**Files:**
- Modify: `features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt`

**Interfaces:**
- Consumes: `TextToSpeechSettingData` (new duration fields)
- Produces: Updated preview composable

- [ ] **Step 1: Add duration fields to preview TextToSpeechSettingData**

In the `ViewsPreview` composable, find the `TextToSpeechSettingData` construction (around line 468) and add after `spokenWordRange`:

```kotlin
        durationProgress = remember { mutableFloatStateOf(0.35f) },
        durationElapsedMs = remember { mutableStateOf(120_000L) },
        durationRemainingMs = remember { mutableStateOf(220_000L) },
        durationTotalMs = remember { mutableStateOf(340_000L) },
```

- [ ] **Step 2: Commit**

```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt
git commit -m "feat(tts): update preview with duration tracker fields"
```

---

### Task 9: Final review and build

- [ ] **Step 1: Review all changes for consistency**

Check that:
- `TTSDurationTracker` is instantiated in `ReaderTextToSpeech` with correct SharedPreferences
- `durationProgress`, `durationElapsedMs`, `durationRemainingMs`, `durationTotalMs` are in `TextToSpeechSettingData`
- `TtsProgressBar` reads correct fields from `TextToSpeechSettingData`
- `VoiceReaderSettingDialog` renders the progress bar in the right position
- Preview data includes the new fields
- No broken imports or missing references

- [ ] **Step 2: Build and verify**

```bash
git add -A
git commit -m "feat(tts): complete TTS duration tracker feature"
git push -u origin feature/tts-duration-tracker
gh workflow run buildRelease.yml --ref feature/tts-duration-tracker -f build_type=test
```

Wait for build to complete:

```bash
gh run list --workflow=buildRelease.yml --limit=1
gh run view <run-id> --json status,conclusion
```
