from pathlib import Path
import re

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, value: str) -> None:
    (ROOT / path).write_text(value, encoding='utf-8')


# ---------------------------------------------------------------------------
# App preference
# ---------------------------------------------------------------------------
prefs = 'core/src/main/java/my/noveldokusha/core/appPreferences/AppPreferences.kt'
text = read(prefs)
marker = '''    val TTS_HIGHLIGHT_COLOR = object : Preference<String>("TTS_HIGHLIGHT_COLOR") {\n        override var value by SharedPreference_String(name, preferences, "FFFF6D00")\n    }\n'''
if 'TTS_DURATION_ENABLED' not in text:
    if marker not in text:
        raise RuntimeError('TTS highlight preference marker not found')
    text = text.replace(marker, marker + '''\n    /** Enables measured chapter-level TTS duration and the duration timeline. */\n    val TTS_DURATION_ENABLED = object : Preference<Boolean>("TTS_DURATION_ENABLED") {\n        override var value by SharedPreference_Boolean(name, preferences, false)\n    }\n''', 1)
    write(prefs, text)


# ---------------------------------------------------------------------------
# Strings
# ---------------------------------------------------------------------------
strings = 'strings/src/main/res/values/strings.xml'
text = read(strings)
if 'name="tts_chapter_duration"' not in text:
    close = '</resources>'
    if close not in text:
        raise RuntimeError('strings.xml closing tag not found')
    text = text.replace(close, '    <string name="tts_chapter_duration">TTS chapter duration</string>\n</resources>', 1)
    write(strings, text)


# ---------------------------------------------------------------------------
# Dedicated measured-duration backend
# ---------------------------------------------------------------------------
backend_path = 'tooling/text_to_speech/src/main/java/my/noveldokusha/text_to_speech/ChapterTtsDurationManager.kt'
backend = r'''package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Measures the duration of synthesized TTS audio without playing or retaining the audio.
 * It is deliberately independent from TextToSpeechManager's playback/scroll/highlight path.
 */
class ChapterTtsDurationManager(private val context: Context) {
    data class Config(
        val enginePackage: String,
        val voiceId: String,
        val localeTag: String,
        val speed: Float,
        val pitch: Float,
    )

    sealed interface Result {
        data class Measured(val durationMs: Long) : Result
        data object Unsupported : Result
        data object Empty : Result
        data object Failed : Result
    }

    private data class AudioFormatInfo(
        val sampleRate: Int,
        val audioFormat: Int,
        val channelCount: Int,
    )

    private val cache = context.getSharedPreferences("tts_duration_cache", Context.MODE_PRIVATE)

    @Volatile
    private var activeTts: TextToSpeech? = null

    fun cancel() {
        runCatching { activeTts?.stop() }
    }

    fun cachedDuration(text: String, config: Config): Long? =
        cache.getLong(cacheKey(text, config), Long.MIN_VALUE).takeIf { it > 0L }

    fun cacheDuration(text: String, config: Config, durationMs: Long) {
        if (durationMs > 0L) cache.edit { putLong(cacheKey(text, config), durationMs) }
    }

    /**
     * Blocking on the caller thread is intentional: callers must invoke this from an IO/worker
     * context. The method retains only byte/frame counters, never the synthesized audio itself.
     */
    fun measureBlocking(
        text: String,
        config: Config,
        timeoutPerSliceMs: Long = 30_000L,
    ): Result {
        if (text.isBlank()) return Result.Empty
        cachedDuration(text, config)?.let { return Result.Measured(it) }

        val ready = CountDownLatch(1)
        val initError = AtomicReference<Int?>(null)
        val tts = try {
            if (config.enginePackage.isBlank()) {
                TextToSpeech(context, { status ->
                    if (status != TextToSpeech.SUCCESS) initError.set(status)
                    ready.countDown()
                })
            } else {
                TextToSpeech(context, { status ->
                    if (status != TextToSpeech.SUCCESS) initError.set(status)
                    ready.countDown()
                }, config.enginePackage)
            }
        } catch (_: Throwable) {
            return Result.Unsupported
        }
        activeTts = tts

        return try {
            if (!ready.await(10, TimeUnit.SECONDS) || initError.get() != null) return Result.Unsupported

            val voice = tts.voices?.firstOrNull { it.name == config.voiceId }
                ?: tts.voice
                ?: return Result.Unsupported
            tts.voice = voice
            if (tts.setSpeechRate(config.speed) != TextToSpeech.SUCCESS) return Result.Unsupported
            if (tts.setPitch(config.pitch) != TextToSpeech.SUCCESS) return Result.Unsupported

            val slices = delimiterAwareTextSplitter(
                fullText = text,
                maxSliceLength = TextToSpeech.getMaxSpeechInputLength(),
                charDelimiter = '.',
            ).filter(String::isNotBlank)
            if (slices.isEmpty()) return Result.Empty

            var totalMs = 0L
            slices.forEachIndexed { index, slice ->
                val finished = CountDownLatch(1)
                val formatRef = AtomicReference<AudioFormatInfo?>(null)
                val failed = AtomicBoolean(false)
                val audioBytes = AtomicReference(0L)
                val utteranceId = "duration-${index}-${System.nanoTime()}"

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onBeginSynthesis(
                        utteranceId: String?,
                        sampleRateInHz: Int,
                        audioFormat: Int,
                        channelCount: Int,
                    ) {
                        if (sampleRateInHz > 0 && channelCount > 0) {
                            formatRef.set(AudioFormatInfo(sampleRateInHz, audioFormat, channelCount))
                        } else {
                            failed.set(true)
                        }
                    }

                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                        if (audio != null) audioBytes.set(audioBytes.get() + audio.size.toLong())
                    }

                    override fun onDone(utteranceId: String?) {
                        finished.countDown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        failed.set(true)
                        finished.countDown()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        failed.set(true)
                        finished.countDown()
                    }
                })

                val pfd = runCatching {
                    ParcelFileDescriptor.open(
                        File("/dev/null"),
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                    )
                }.getOrNull() ?: return Result.Unsupported

                try {
                    val result = tts.synthesizeToFile(
                        slice,
                        Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                        },
                        pfd,
                        utteranceId,
                    )
                    if (result != TextToSpeech.SUCCESS) return Result.Unsupported
                    if (!finished.await(timeoutPerSliceMs, TimeUnit.MILLISECONDS)) return Result.Failed
                } finally {
                    runCatching { pfd.close() }
                }

                if (failed.get()) return Result.Failed
                val format = formatRef.get() ?: return Result.Unsupported
                val bytesPerSample = when (format.audioFormat) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 1L
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 2L
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4L
                    android.media.AudioFormat.ENCODING_PCM_24BIT -> 3L
                    android.media.AudioFormat.ENCODING_PCM_32BIT -> 4L
                    else -> return Result.Unsupported
                }
                val bytesPerFrame = bytesPerSample * format.channelCount.toLong()
                val bytes = audioBytes.get()
                if (format.sampleRate <= 0 || bytesPerFrame <= 0L || bytes <= 0L || bytes % bytesPerFrame != 0L) {
                    return Result.Failed
                }
                val frames = bytes / bytesPerFrame
                if (frames <= 0L) return Result.Failed
                totalMs += (frames * 1000L) / format.sampleRate.toLong()
                if (totalMs <= 0L) return Result.Failed
            }

            cacheDuration(text, config, totalMs)
            Result.Measured(totalMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Failed
        } catch (_: Throwable) {
            Result.Failed
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
            if (activeTts === tts) activeTts = null
        }
    }

    private fun cacheKey(text: String, config: Config): String {
        val material = buildString {
            append(NORMALIZATION_VERSION).append('|')
            append(config.enginePackage).append('|')
            append(config.voiceId).append('|')
            append(config.localeTag).append('|')
            append(config.speed).append('|')
            append(config.pitch).append('|')
            append(text)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return "duration_v2_" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val NORMALIZATION_VERSION = 1
    }
}
'''
write(backend_path, backend)


# ---------------------------------------------------------------------------
# Tests: keep normalization math explicit and independently testable.
# ---------------------------------------------------------------------------
test_path = 'tooling/text_to_speech/src/test/java/my/noveldokusha/text_to_speech/ChapterTtsDurationNormalizerTest.kt'
test = r'''package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal object TtsDurationMath {
    fun durationMs(frameCount: Long, sampleRate: Int): Long {
        require(frameCount > 0L) { "frameCount must be positive" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        return (frameCount * 1000L) / sampleRate.toLong()
    }
}

class ChapterTtsDurationNormalizerTest {
    @Test fun pcm48kOneSecond() = assertEquals(1000L, TtsDurationMath.durationMs(48_000L, 48_000))
    @Test fun pcm48kTwoSeconds() = assertEquals(2000L, TtsDurationMath.durationMs(96_000L, 48_000))
    @Test fun pcm44kOneSecond() = assertEquals(1000L, TtsDurationMath.durationMs(44_100L, 44_100))
    @Test fun zeroFramesRejected() = assertThrows(IllegalArgumentException::class.java) { TtsDurationMath.durationMs(0L, 48_000) }
    @Test fun zeroRateRejected() = assertThrows(IllegalArgumentException::class.java) { TtsDurationMath.durationMs(1_000L, 0) }
}
'''
write(test_path, test)


# ---------------------------------------------------------------------------
# Reader TTS state + duration integration
# ---------------------------------------------------------------------------
reader = 'features/reader/src/main/java/my/noveldokusha/features/reader/features/ReaderTextToSpeech.kt'
text = read(reader)

if 'import my.noveldokusha.core.appPreferences.AppPreferences\n' not in text:
    text = text.replace(
        'import my.noveldokusha.core.appPreferences.VoicePredefineState\n',
        'import my.noveldokusha.core.appPreferences.VoicePredefineState\nimport my.noveldokusha.core.appPreferences.AppPreferences\n',
        1,
    )
if 'import my.noveldokusha.text_to_speech.ChapterTtsDurationManager\n' not in text:
    text = text.replace(
        'import my.noveldokusha.text_to_speech.AppTtsEngine\n',
        'import my.noveldokusha.text_to_speech.AppTtsEngine\nimport my.noveldokusha.text_to_speech.ChapterTtsDurationManager\n',
        1,
    )

if '    val ttsDurationEnabled: State<Boolean>,' not in text:
    state_marker = '    val spokenWordRange: State<IntRange?>,\n'
    state_add = state_marker + '''    val ttsDurationEnabled: State<Boolean>,\n    val chapterTtsDurationMs: State<Long?>,\n    val chapterTtsDurationCurrentMs: State<Long>,\n    val chapterTtsDurationRemainingMs: State<Long>,\n    val chapterTtsDurationProgress: State<Float>,\n    val chapterTtsDurationLoading: State<Boolean>,\n    val chapterTtsDurationProvisional: State<Boolean>,\n    val setTtsDurationEnabled: (Boolean) -> Unit,\n'''
    if state_marker not in text:
        raise RuntimeError('TextToSpeechSettingData marker not found')
    text = text.replace(state_marker, state_add, 1)

manager_marker = '''    private val manager = TextToSpeechManager(\n'''
if 'private val chapterTtsDurationManager' not in text:
    manager_add = '''    private val appPreferences = AppPreferences(context.applicationContext)\n    private val chapterTtsDurationManager = ChapterTtsDurationManager(context.applicationContext)\n    private val ttsDurationEnabledState = mutableStateOf(appPreferences.TTS_DURATION_ENABLED.value)\n    private val chapterTtsDurationMsState = mutableStateOf<Long?>(null)\n    private val chapterTtsDurationCurrentMsState = mutableStateOf(0L)\n    private val chapterTtsDurationLoadingState = mutableStateOf(false)\n    private val chapterTtsDurationProvisionalState = mutableStateOf(false)\n    private var chapterTtsDurationMeasurementJob: Job? = null\n    private var chapterTtsDurationRequestKey: String? = null\n    private var lastMeasuredDurationSpeed = 1f\n    private var durationClockChapterIndex = -1\n    private var durationClockElapsedMs = 0L\n    private var durationClockStartedAtMs = 0L\n    private var durationClockJob: Job? = null\n\n'''
    if manager_marker not in text:
        raise RuntimeError('TTS manager marker not found')
    text = text.replace(manager_marker, manager_add + manager_marker, 1)

construct_marker = '''        spokenWordRange = manager.spokenWordRange,\n    )\n'''
if 'chapterTtsDurationRemainingMs = derivedStateOf' not in text:
    construct_add = '''        spokenWordRange = manager.spokenWordRange,\n        ttsDurationEnabled = ttsDurationEnabledState,\n        chapterTtsDurationMs = chapterTtsDurationMsState,\n        chapterTtsDurationCurrentMs = chapterTtsDurationCurrentMsState,\n        chapterTtsDurationRemainingMs = derivedStateOf {\n            ((chapterTtsDurationMsState.value ?: 0L) - chapterTtsDurationCurrentMsState.value).coerceAtLeast(0L)\n        },\n        chapterTtsDurationProgress = derivedStateOf {\n            val total = chapterTtsDurationMsState.value ?: 0L\n            if (total > 0L) (chapterTtsDurationCurrentMsState.value.toFloat() / total).coerceIn(0f, 1f) else 0f\n        },\n        chapterTtsDurationLoading = chapterTtsDurationLoadingState,\n        chapterTtsDurationProvisional = chapterTtsDurationProvisionalState,\n        setTtsDurationEnabled = ::setTtsDurationEnabled,\n    )\n'''
    if construct_marker not in text:
        raise RuntimeError('state constructor marker not found')
    text = text.replace(construct_marker, construct_add, 1)

helper_marker = '    private fun switchVoiceForMode() {\n'
if 'private fun requestChapterTtsDuration' not in text:
    helpers = r'''    private fun chapterSpokenText(chapterIndex: Int): String =
        items.filterIsInstance<ReaderItem.Text>()
            .filter { it.chapterIndex == chapterIndex }
            .joinToString("\n") { ttsText(it) }

    private fun currentDurationConfig(): ChapterTtsDurationManager.Config? {
        val voice = manager.activeVoice.value ?: return null
        return ChapterTtsDurationManager.Config(
            enginePackage = voice.enginePackage,
            voiceId = voice.id,
            localeTag = manager.service.voice?.locale?.toLanguageTag() ?: "",
            speed = manager.voiceSpeed.floatValue,
            pitch = manager.voicePitch.floatValue,
        )
    }

    private fun durationRequestKey(chapterIndex: Int, text: String, config: ChapterTtsDurationManager.Config): String =
        "$chapterIndex|${config.enginePackage}|${config.voiceId}|${config.localeTag}|${config.speed}|${config.pitch}|${text.hashCode()}"

    private fun requestChapterTtsDuration(chapterIndex: Int) {
        if (!ttsDurationEnabledState.value || !isChapterIndexValid(chapterIndex)) return
        val text = chapterSpokenText(chapterIndex)
        if (text.isBlank()) return
        val config = currentDurationConfig() ?: return
        val requestKey = durationRequestKey(chapterIndex, text, config)
        if (chapterTtsDurationRequestKey == requestKey &&
            (chapterTtsDurationLoadingState.value || chapterTtsDurationMsState.value != null)
        ) return

        chapterTtsDurationRequestKey = requestKey
        chapterTtsDurationMeasurementJob?.cancel()
        chapterTtsDurationManager.cancel()

        chapterTtsDurationManager.cachedDuration(text, config)?.let { cached ->
            chapterTtsDurationMsState.value = cached
            chapterTtsDurationLoadingState.value = false
            chapterTtsDurationProvisionalState.value = false
            lastMeasuredDurationSpeed = config.speed
            return
        }

        val previous = chapterTtsDurationMsState.value
        if (previous != null && lastMeasuredDurationSpeed > 0f && config.speed > 0f) {
            chapterTtsDurationMsState.value = (previous * lastMeasuredDurationSpeed / config.speed).toLong().coerceAtLeast(1L)
            chapterTtsDurationProvisionalState.value = true
        } else {
            chapterTtsDurationProvisionalState.value = false
        }
        chapterTtsDurationLoadingState.value = true

        chapterTtsDurationMeasurementJob = coroutineScope.launch(Dispatchers.IO) {
            when (val result = chapterTtsDurationManager.measureBlocking(text, config)) {
                is ChapterTtsDurationManager.Result.Measured -> withContext(Dispatchers.Main.immediate) {
                    if (ttsDurationEnabledState.value && chapterTtsDurationRequestKey == requestKey) {
                        chapterTtsDurationMsState.value = result.durationMs
                        chapterTtsDurationLoadingState.value = false
                        chapterTtsDurationProvisionalState.value = false
                        lastMeasuredDurationSpeed = config.speed
                    }
                }
                else -> withContext(Dispatchers.Main.immediate) {
                    if (chapterTtsDurationRequestKey == requestKey) {
                        chapterTtsDurationLoadingState.value = false
                    }
                }
            }
        }
    }

    private fun setTtsDurationEnabled(enabled: Boolean) {
        ttsDurationEnabledState.value = enabled
        appPreferences.TTS_DURATION_ENABLED.value = enabled
        if (!enabled) {
            chapterTtsDurationMeasurementJob?.cancel()
            chapterTtsDurationMeasurementJob = null
            chapterTtsDurationManager.cancel()
            chapterTtsDurationRequestKey = null
            chapterTtsDurationLoadingState.value = false
            chapterTtsDurationProvisionalState.value = false
            durationClockJob?.cancel()
            durationClockJob = null
            return
        }
        val chapter = manager.currentActiveItemState.value.itemPos.chapterIndex
        if (isChapterIndexValid(chapter)) requestChapterTtsDuration(chapter)
    }

    private fun resetDurationClock(chapterIndex: Int) {
        if (!ttsDurationEnabledState.value || !isChapterIndexValid(chapterIndex)) return
        durationClockChapterIndex = chapterIndex
        durationClockElapsedMs = 0L
        durationClockStartedAtMs = if (state.isPlaying.value) SystemClock.elapsedRealtime() else 0L
        chapterTtsDurationCurrentMsState.value = 0L
        chapterTtsDurationRequestKey = null
        requestChapterTtsDuration(chapterIndex)
    }

    private fun startDurationClock(chapterIndex: Int) {
        if (!ttsDurationEnabledState.value || !isChapterIndexValid(chapterIndex)) return
        if (durationClockChapterIndex != chapterIndex) resetDurationClock(chapterIndex)
        if (durationClockStartedAtMs == 0L) durationClockStartedAtMs = SystemClock.elapsedRealtime()
        durationClockJob?.cancel()
        durationClockJob = coroutineScope.launch(Dispatchers.Default) {
            while (ttsDurationEnabledState.value && state.isPlaying.value) {
                val now = SystemClock.elapsedRealtime()
                val current = durationClockElapsedMs + (now - durationClockStartedAtMs).coerceAtLeast(0L)
                withContext(Dispatchers.Main.immediate) {
                    if (ttsDurationEnabledState.value) {
                        chapterTtsDurationCurrentMsState.value = if ((chapterTtsDurationMsState.value ?: 0L) > 0L) {
                            current.coerceAtMost(chapterTtsDurationMsState.value ?: Long.MAX_VALUE)
                        } else current
                    }
                }
                delay(500L)
            }
        }
    }

    private fun pauseDurationClock() {
        if (durationClockStartedAtMs != 0L) {
            durationClockElapsedMs += (SystemClock.elapsedRealtime() - durationClockStartedAtMs).coerceAtLeast(0L)
            durationClockStartedAtMs = 0L
            chapterTtsDurationCurrentMsState.value = durationClockElapsedMs
        }
        durationClockJob?.cancel()
        durationClockJob = null
    }

'''
    if helper_marker not in text:
        raise RuntimeError('helper insertion marker not found')
    text = text.replace(helper_marker, helpers + helper_marker, 1)

# Start/stop lifecycle
start_marker = '''            state.isPlaying.value = true\n            updateJob?.cancel()\n'''
if start_marker in text and 'startDurationClock(manager.currentActiveItemState.value.itemPos.chapterIndex)' not in text:
    text = text.replace(start_marker, '''            state.isPlaying.value = true\n            startDurationClock(manager.currentActiveItemState.value.itemPos.chapterIndex)\n            updateJob?.cancel()\n''', 1)
stop_marker = '''            state.isPlaying.value = false\n            updateJob?.cancel()\n            manager.stop()\n'''
if stop_marker in text and 'pauseDurationClock()' not in text:
    text = text.replace(stop_marker, '''            state.isPlaying.value = false\n            pauseDurationClock()\n            updateJob?.cancel()\n            manager.stop()\n''', 1)

# New chapter reset at read entry
chapter_marker = '''    suspend fun readChapterStartingFromItemIndex(\n        itemIndex: Int,\n        chapterIndex: Int,\n    ) = withContext(Dispatchers.Main.immediate) {\n'''
if chapter_marker in text and 'resetDurationClock(chapterIndex)' not in text:
    text = text.replace(chapter_marker, chapter_marker + '''        if (ttsDurationEnabledState.value && durationClockChapterIndex != chapterIndex) {\n            resetDurationClock(chapterIndex)\n        }\n\n''', 1)

# Configuration changes trigger a duration request after successful change.
pitch_block = '''        if (success) {\n            setPreferredVoicePitch(value)\n            resumeFromCurrentState()\n        }\n'''
if pitch_block in text and 'setPreferredVoicePitch(value)\n            resumeFromCurrentState()\n            requestChapterTtsDuration' not in text:
    text = text.replace(pitch_block, '''        if (success) {\n            setPreferredVoicePitch(value)\n            resumeFromCurrentState()\n            requestChapterTtsDuration(state.currentActiveItemState.value.itemPos.chapterIndex)\n        }\n''', 1)

speed_block = '''        if (success) {\n            setPreferredVoiceSpeed(value)\n            resumeFromCurrentState()\n        }\n'''
if speed_block in text and 'setPreferredVoiceSpeed(value)\n            resumeFromCurrentState()\n            requestChapterTtsDuration' not in text:
    text = text.replace(speed_block, '''        if (success) {\n            setPreferredVoiceSpeed(value)\n            resumeFromCurrentState()\n            requestChapterTtsDuration(state.currentActiveItemState.value.itemPos.chapterIndex)\n        }\n''', 1)

voice_block = '''                if (voiceData != null) setPreferredVoiceEngine(voiceData.enginePackage)\n                resumeFromCurrentState()\n'''
if voice_block in text and 'setPreferredVoiceEngine(voiceData.enginePackage)\n                resumeFromCurrentState()\n                requestChapterTtsDuration' not in text:
    text = text.replace(voice_block, '''                if (voiceData != null) setPreferredVoiceEngine(voiceData.enginePackage)\n                resumeFromCurrentState()\n                requestChapterTtsDuration(state.currentActiveItemState.value.itemPos.chapterIndex)\n''', 1)

write(reader, text)


# ---------------------------------------------------------------------------
# Reader TTS settings UI: toggle below floating-TTS controls.
# ---------------------------------------------------------------------------
dialog = 'features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt'
text = read(dialog)
if 'import androidx.compose.material3.Switch\n' not in text:
    text = text.replace('import androidx.compose.material3.Surface\n', 'import androidx.compose.material3.Surface\nimport androidx.compose.material3.Switch\n', 1)
if 'stringResource(R.string.tts_chapter_duration)' not in text:
    pattern = re.compile(r'(                if \(floatingTtsState != null\) \{.*?                \}\n\n)(                Row\(\n                    horizontalArrangement = Arrangement.Center,)', re.S)
    match = pattern.search(text)
    if not match:
        raise RuntimeError('VoiceReaderSettingDialog insertion point not found')
    row = '''                Row(\n                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                    verticalAlignment = Alignment.CenterVertically,\n                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),\n                ) {\n                    Text(\n                        text = stringResource(R.string.tts_chapter_duration),\n                        style = MaterialTheme.typography.bodyMedium,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                        modifier = Modifier.weight(1f),\n                    )\n                    Switch(\n                        checked = state.ttsDurationEnabled.value,\n                        onCheckedChange = state.setTtsDurationEnabled,\n                    )\n                }\n\n'''
    text = text[:match.end(1)] + row + text[match.end(1):]
    write(dialog, text)


# ---------------------------------------------------------------------------
# Floating TTS timeline. Existing UI path remains untouched when disabled.
# ---------------------------------------------------------------------------
mini = 'features/reader/src/main/java/my/noveldokusha/features/reader/ui/TtsMiniPlayer.kt'
text = read(mini)
if 'import androidx.compose.runtime.saveable.rememberSaveable\n' not in text:
    text = text.replace('import androidx.compose.runtime.remember\n', 'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.saveable.rememberSaveable\n', 1)

old = '''    val total = state.estimatedTotalSeconds.value\n    val remaining = state.estimatedRemainingSeconds.value\n    val progress = if (total > 0) (total - remaining).toFloat() / total else 0f\n'''
new = '''    val durationEnabled = state.ttsDurationEnabled.value\n    val total = if (durationEnabled) (state.chapterTtsDurationMs.value?.div(1000L) ?: 0L).toInt() else state.estimatedTotalSeconds.value\n    val remaining = if (durationEnabled) state.chapterTtsDurationRemainingMs.value.div(1000L).toInt() else state.estimatedRemainingSeconds.value\n    val current = if (durationEnabled) state.chapterTtsDurationCurrentMs.value.div(1000L).toInt() else (total - remaining).coerceAtLeast(0)\n    val progress = if (durationEnabled) state.chapterTtsDurationProgress.value else if (total > 0) (total - remaining).toFloat() / total else 0f\n    var showTotalDuration by rememberSaveable { mutableStateOf(false) }\n'''
if old in text:
    text = text.replace(old, new, 1)

sig = '''    animatedProgress: Float,\n    remaining: Int,\n'''
if sig in text and 'showTotalDuration: Boolean' not in text:
    text = text.replace(sig, '''    animatedProgress: Float,\n    current: Int,\n    remaining: Int,\n    durationEnabled: Boolean,\n    showTotalDuration: Boolean,\n    onToggleDurationMode: () -> Unit,\n''', 1)

surface = re.compile(r'''        Surface\(\n            shape = RoundedCornerShape\(16\.dp\),\n            color = MaterialTheme\.colorScheme\.primaryContainer,\n        \) \{\n            Row\(\n                verticalAlignment = Alignment\.CenterVertically,\n                modifier = Modifier\.padding\(horizontal = badgeHorizPad, vertical = badgeVertPad\)\n            \) \{\n                Icon\(\n                    Icons\.Rounded\.AccessTime,\n                    contentDescription = null,\n                    tint = MaterialTheme\.colorScheme\.onPrimaryContainer,\n                    modifier = Modifier\.size\(14\.dp\)\n                \)\n                Spacer\(Modifier\.width\(4\.dp\)\)\n                Text\(\n                    text = formatDuration\(remaining\),\n                    style = MaterialTheme\.typography\.labelSmall,\n                    fontWeight = FontWeight\.Bold,\n                    color = MaterialTheme\.colorScheme\.onPrimaryContainer,\n                \)\n            \}\n        \}\n''')
replacement = '''        if (durationEnabled) {\n            Text(\n                text = formatDuration(current),\n                style = MaterialTheme.typography.labelSmall,\n                fontWeight = FontWeight.Medium,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n            )\n            Text(\n                text = if (showTotalDuration) formatDuration((state.chapterTtsDurationMs.value ?: 0L).div(1000L).toInt()) else "-${formatDuration(remaining)}",\n                style = MaterialTheme.typography.labelSmall,\n                fontWeight = FontWeight.Medium,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n                modifier = Modifier\n                    .clip(RoundedCornerShape(4.dp))\n                    .clickable(onClick = onToggleDurationMode)\n                    .padding(horizontal = 2.dp, vertical = 1.dp),\n            )\n        } else {\n            Surface(\n                shape = RoundedCornerShape(16.dp),\n                color = MaterialTheme.colorScheme.primaryContainer,\n            ) {\n                Row(\n                    verticalAlignment = Alignment.CenterVertically,\n                    modifier = Modifier.padding(horizontal = badgeHorizPad, vertical = badgeVertPad)\n                ) {\n                    Icon(\n                        Icons.Rounded.AccessTime,\n                        contentDescription = null,\n                        tint = MaterialTheme.colorScheme.onPrimaryContainer,\n                        modifier = Modifier.size(14.dp)\n                    )\n                    Spacer(Modifier.width(4.dp))\n                    Text(\n                        text = formatDuration(remaining),\n                        style = MaterialTheme.typography.labelSmall,\n                        fontWeight = FontWeight.Bold,\n                        color = MaterialTheme.colorScheme.onPrimaryContainer,\n                    )\n                }\n            }\n        }\n'''
if surface.search(text):
    text = surface.sub(replacement, text, count=1)

call = '''                        animatedProgress = animatedProgress,\n                        remaining = remaining,\n'''
if call in text and 'current = current,\n                        remaining = remaining' not in text:
    text = text.replace(call, '''                        animatedProgress = animatedProgress,\n                        current = current,\n                        remaining = remaining,\n                        durationEnabled = durationEnabled,\n                        showTotalDuration = showTotalDuration,\n                        onToggleDurationMode = { showTotalDuration = !showTotalDuration },\n''', 1)

write(mini, text)

print('TTS duration implementation applied')
