package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports a chapter to WAV and captures synchronization metadata for that exact
 * PCM stream. Export TTS clients are obtained from [TtsAudioEnginePool] and are
 * completely separate from the reader's [TextToSpeechManager].
 *
 * Export always uses TextToSpeech.synthesizeToFile(), never speak(). This keeps
 * the export silent without paying the much higher playback-path latency.
 * onAudioAvailable() supplies the exact PCM written to the final WAV; the
 * synthesizeToFile() destination is a temporary scratch file required by some
 * engines to reliably emit native onRangeStart() callbacks.
 */
class TtsAudioExporter(
    private val context: Context,
) {
    suspend fun exportAudio(
        request: TtsAudioExportRequest,
        paragraphs: List<String>,
        destFile: File,
        audioFileName: String,
        onProgress: (Float) -> Unit = {},
    ): TtsAudioExportResult {
        if (paragraphs.isEmpty()) {
            throw TtsExportException("Chapter has no text to synthesize")
        }

        val writer = WavWriter(destFile)
        val timelineBuilder = TtsTimelineBuilder()
        timelineBuilder.beginChapter(
            novelTitle = request.novelTitle,
            chapterTitle = request.chapterTitle,
            chapterIndex = request.chapterIndex,
            source = request.source.name,
        )

        val liveEnginePackage = AppTtsEngine.getInstance(context).getBoundEnginePackage().orEmpty()
        val resolvedEngine = TtsEngineCatalog.resolveForExport(
            context = context,
            requestedEnginePackage = request.enginePackage,
            requestedVoiceId = request.voiceId,
            liveEnginePackage = liveEnginePackage,
        )
        val effectiveRequest = request.copy(
            enginePackage = resolvedEngine.enginePackage,
            voiceId = resolvedEngine.voiceId,
        )
        if (resolvedEngine.changedEngine || resolvedEngine.changedVoice) {
            Timber.w(
                "ttsExport isolated from live engine=$liveEnginePackage " +
                    "requested=${request.enginePackage}/${request.voiceId} " +
                    "effective=${effectiveRequest.enginePackage}/${effectiveRequest.voiceId} " +
                    "changedVoice=${resolvedEngine.changedVoice}"
            )
        }

        // Hold one export slot for the whole chapter rather than reacquiring the pool
        // for every slice. This keeps the five-export limit meaningful and avoids repeated
        // engine-client churn while preserving independent clients for different chapters.
        val lease = TtsAudioEnginePool.acquire(context, effectiveRequest.enginePackage)
        var finished = false
        try {
            val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
            val novelTitle = TtsTextPreparer.cleanForTts(effectiveRequest.novelTitle).trim()
            val chapterTitle = TtsTextPreparer.cleanForTts(effectiveRequest.chapterTitle).trim()
            val paragraphPlans = buildList {
                if (novelTitle.isNotBlank() && !TtsTextPreparer.isOnlyDecorators(novelTitle)) {
                    add(novelTitle to TtsTextPreparer.chunkIntoUtterances(novelTitle, maxInputLength))
                }
                if (chapterTitle.isNotBlank() && !TtsTextPreparer.isOnlyDecorators(chapterTitle)) {
                    add(chapterTitle to TtsTextPreparer.chunkIntoUtterances(chapterTitle, maxInputLength))
                }
                for (paragraph in paragraphs) {
                    val cleaned = TtsTextPreparer.cleanForTts(paragraph)
                    if (TtsTextPreparer.isOnlyDecorators(cleaned)) continue
                    add(cleaned to TtsTextPreparer.chunkIntoUtterances(cleaned, maxInputLength))
                }
            }
            if (paragraphPlans.isEmpty()) {
                throw TtsExportException("Chapter has no text, novel title, or chapter title to synthesize")
            }
            val totalChars = paragraphPlans.sumOf { it.first.length }

            var synthesizedAny = false
            var processedChars = 0
            var chunkIndex = 0
            for ((_, slices) in paragraphPlans) {
                timelineBuilder.beginParagraph()
                for (chunk in slices) {
                    val tts = lease.tts
                    configureTts(tts, effectiveRequest)
                    timelineBuilder.registerSlice(chunk)
                    synthesizeChunk(
                        tts = tts,
                        text = chunk,
                        writer = writer,
                        chapterTitle = effectiveRequest.chapterTitle,
                        timelineBuilder = timelineBuilder,
                        utteranceId = "tts_export_${effectiveRequest.jobId}_$chunkIndex",
                        stopOnCancellation = effectiveRequest.enginePackage != liveEnginePackage,
                    )

                    chunkIndex++
                    processedChars += chunk.length
                    if (writer.dataBytesWritten() > 0) synthesizedAny = true
                    onProgress(
                        if (totalChars > 0) processedChars.toFloat() / totalChars.toFloat() else 1f
                    )
                }
                timelineBuilder.endParagraph()
            }

            if (!synthesizedAny) {
                throw TtsExportException(
                    "No audio was produced for chapter '${effectiveRequest.chapterTitle}'. " +
                        "The TTS engine produced an empty result."
                )
            }

            writer.finish()

            val sampleRate = writer.sampleRate()
            val channels = writer.channels()
            val durationMs = if (sampleRate > 0 && channels > 0) {
                ((writer.dataBytesWritten().toDouble() * 1000.0) /
                    (sampleRate.toDouble() * channels.toDouble() * 2.0)).toInt()
            } else {
                0
            }
            if (sampleRate <= 0 || channels <= 0) {
                throw TtsExportException("Chapter '${effectiveRequest.chapterTitle}' has no valid audio format")
            }

            val timeline = timelineBuilder.build(
                audioFileName = audioFileName,
                audioSampleRate = sampleRate,
                audioChannels = channels,
                audioDurationMs = durationMs,
            )
            val rangeCount = timeline.paragraphs.sumOf { it.ranges.size }
            if (rangeCount == 0) {
                throw TtsExportException(
                    "TTS engine '${effectiveRequest.enginePackage}' produced audio but no native " +
                        "range timing data for chapter '${effectiveRequest.chapterTitle}'. " +
                        "The exported JSON would not contain word-highlight timing."
                )
            }
            Timber.d(
                "ttsExport timeline ready: chapter=${effectiveRequest.chapterTitle}, " +
                    "engine=${effectiveRequest.enginePackage}, ranges=$rangeCount, durationMs=$durationMs"
            )
            finished = true
            return TtsAudioExportResult(audioFile = destFile, timeline = timeline)
        } finally {
            lease.close()
            if (!finished) runCatching { writer.close() }
        }
    }

    private fun configureTts(tts: TextToSpeech, request: TtsAudioExportRequest) {
        val voices = tts.voices ?: emptyList()
        val voice = voices.find { it.name == request.voiceId }
            ?: throw TtsExportException(
                "Voice '${request.voiceId}' not found in engine '${request.enginePackage}'"
            )
        tts.voice = voice
        val speedResult = tts.setSpeechRate(request.speed)
        if (speedResult != TextToSpeech.SUCCESS) {
            throw TtsExportException("Failed to set TTS speech rate for '${request.chapterTitle}'")
        }
        val pitchResult = tts.setPitch(request.pitch)
        if (pitchResult != TextToSpeech.SUCCESS) {
            throw TtsExportException("Failed to set TTS pitch for '${request.chapterTitle}'")
        }
    }

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        writer: WavWriter,
        chapterTitle: String,
        timelineBuilder: TtsTimelineBuilder,
        utteranceId: String,
        stopOnCancellation: Boolean,
    ) {
        val done = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val rangeNormalizer = TtsNativeRangeNormalizer(text.length)
        val scratchFile = File.createTempFile("tts_export_", ".wav", context.cacheDir)

        fun failOnce(error: Throwable) {
            if (!done.isCompleted) done.completeExceptionally(error)
        }

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (!cancelled.get()) done.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!cancelled.get()) {
                    done.completeExceptionally(
                        TtsExportException("Synthesis failed (error $errorCode) for '$chapterTitle'")
                    )
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!cancelled.get()) {
                    done.completeExceptionally(
                        TtsExportException("Synthesis failed for '$chapterTitle'")
                    )
                }
            }

            override fun onBeginSynthesis(
                utteranceId: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                if (cancelled.get()) return
                if (sampleRateInHz <= 0 || channelCount <= 0) {
                    failOnce(
                        TtsExportException(
                            "TTS engine returned invalid audio format: ${sampleRateInHz}Hz/$channelCount channels"
                        )
                    )
                    return
                }
                if (!writer.isValidPcmFormat(audioFormat)) {
                    failOnce(
                        TtsExportException(
                            "TTS engine returned unsupported audio format: $audioFormat (PCM16 required)"
                        )
                    )
                    return
                }
                try {
                    writer.open(sampleRateInHz, channelCount)
                    timelineBuilder.setSliceFormat(sampleRateInHz, channelCount)
                } catch (e: Exception) {
                    failOnce(TtsExportException("Failed initializing audio writer: ${e.message}", e))
                }
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                if (cancelled.get() || audio.isEmpty()) return
                try {
                    timelineBuilder.onAudioAvailable(audio.size)
                    writer.writePcm(audio)
                } catch (e: Throwable) {
                    Timber.e(e, "ttsExport writing PCM failed id=$utteranceId")
                    failOnce(TtsExportException("Failed writing audio data: ${e.message}", e))
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (cancelled.get()) return
                try {
                    val normalized = rangeNormalizer.normalize(start, end, frame)
                        ?: throw TtsExportException(
                            "Invalid native TTS range callback for text length ${text.length}: " +
                                "($start,$end,$frame)"
                        )
                    timelineBuilder.onRangeStart(
                        start = normalized.start,
                        end = normalized.end,
                        frame = normalized.frame,
                    )
                } catch (e: Throwable) {
                    Timber.e(e, "ttsExport native range capture failed id=$utteranceId")
                    failOnce(TtsExportException("Failed capturing native TTS timing: ${e.message}", e))
                }
            }
        }
        tts.setOnUtteranceProgressListener(listener)

        val result = tts.synthesizeToFile(
            text,
            Bundle(),
            scratchFile,
            utteranceId,
        )
        if (result != TextToSpeech.SUCCESS) {
            failOnce(TtsExportException("synthesizeToFile rejected input (result=$result)"))
        }

        try {
            done.await()
        } catch (e: CancellationException) {
            cancelled.set(true)
            if (stopOnCancellation) {
                // Export is using an engine service different from the live reader, so stop
                // is safe here and makes cancellation responsive instead of waiting forever.
                runCatching { tts.stop() }
                throw e
            }

            // If the device has only one engine, preserve the old safety behavior: stopping
            // the shared engine can also interrupt the live reader. Mark callbacks inert and
            // wait for the in-flight request to settle before releasing the shared client.
            withContext(NonCancellable) {
                runCatching { done.await() }
            }
            throw e
        } finally {
            runCatching { scratchFile.delete() }
        }
    }
}

internal data class NormalizedTtsRange(
    val start: Int,
    val end: Int,
    val frame: Int,
)

internal class TtsNativeRangeNormalizer(
    private val textLength: Int,
) {
    private enum class Order { UNKNOWN, NORMAL, SWAPPED }

    private var order = Order.UNKNOWN

    fun normalize(start: Int, end: Int, frame: Int): NormalizedTtsRange? {
        val normalValid = isValidTextRange(start, end)
        val swappedValid = isValidTextRange(end, frame)
        val swappedClearlyIndicated = swappedValid && (
            start >= textLength ||
                frame > textLength ||
                !normalValid
            )

        if (order == Order.UNKNOWN) {
            order = when {
                swappedClearlyIndicated -> Order.SWAPPED
                normalValid && !swappedValid -> Order.NORMAL
                !normalValid && swappedValid -> Order.SWAPPED
                normalValid && swappedValid -> Order.NORMAL
                else -> Order.UNKNOWN
            }
        }

        return when (order) {
            Order.NORMAL -> if (normalValid) {
                NormalizedTtsRange(start, end, frame)
            } else if (swappedValid) {
                order = Order.SWAPPED
                NormalizedTtsRange(end, frame, start)
            } else {
                null
            }
            Order.SWAPPED -> if (swappedValid) {
                NormalizedTtsRange(end, frame, start)
            } else if (normalValid) {
                order = Order.NORMAL
                NormalizedTtsRange(start, end, frame)
            } else {
                null
            }
            Order.UNKNOWN -> null
        }
    }

    private fun isValidTextRange(start: Int, end: Int): Boolean =
        start >= 0 && start < end && end <= textLength
}

class TtsExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
