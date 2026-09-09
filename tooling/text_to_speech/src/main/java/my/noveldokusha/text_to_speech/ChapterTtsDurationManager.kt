package my.noveldokusha.text_to_speech

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
