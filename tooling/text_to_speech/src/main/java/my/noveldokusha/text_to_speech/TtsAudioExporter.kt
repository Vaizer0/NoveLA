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

/**
 * Exports a chapter to WAV and captures synchronization metadata for that exact
 * PCM stream. Export TTS clients are obtained from [TtsAudioEnginePool] and are
 * completely separate from the reader's [TextToSpeechManager].
 *
 * Export uses TextToSpeech.synthesizeToFile(), never speak(). This keeps export
 * silent and prevents export work from flushing or playing through the reader's
 * live TTS playback queue. PCM is still captured from onAudioAvailable().
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

        val lease = TtsAudioEnginePool.acquire(context, request.enginePackage)
        val tts = lease.tts
        var finished = false
        try {
            val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
            val voices = tts.voices ?: emptyList()
            val voice = voices.find { it.name == request.voiceId }
                ?: throw TtsExportException(
                    "Voice '${request.voiceId}' not found in engine '${request.enginePackage}'"
                )
            tts.voice = voice
            tts.setSpeechRate(request.speed)
            tts.setPitch(request.pitch)

            val paragraphPlans = buildList {
                for (paragraph in paragraphs) {
                    val cleaned = TtsTextPreparer.cleanForTts(paragraph)
                    if (TtsTextPreparer.isOnlyDecorators(cleaned)) continue
                    add(cleaned to TtsTextPreparer.chunkIntoUtterances(cleaned, maxInputLength))
                }
            }
            val totalChars = paragraphPlans.sumOf { it.first.length }

            var synthesizedAny = false
            var processedChars = 0
            var chunkIndex = 0
            for ((_, slices) in paragraphPlans) {
                timelineBuilder.beginParagraph()
                for (chunk in slices) {
                    timelineBuilder.registerSlice(chunk)
                    synthesizeChunk(
                        tts = tts,
                        text = chunk,
                        writer = writer,
                        chapterTitle = request.chapterTitle,
                        timelineBuilder = timelineBuilder,
                        utteranceId = "tts_export_${request.jobId}_$chunkIndex",
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
                    "No audio was produced for chapter '${request.chapterTitle}'. " +
                        "The TTS engine produced an empty result."
                )
            }

            writer.finish()
            finished = true

            val sampleRate = writer.sampleRate()
            val channels = writer.channels()
            val durationMs = if (sampleRate > 0 && channels > 0) {
                ((writer.dataBytesWritten().toDouble() * 1000.0) /
                    (sampleRate.toDouble() * channels.toDouble() * 2.0)).toInt()
            } else {
                0
            }
            if (sampleRate <= 0 || channels <= 0) {
                throw TtsExportException("Chapter '${request.chapterTitle}' has no valid audio format")
            }

            val timeline = timelineBuilder.build(
                audioFileName = audioFileName,
                audioSampleRate = sampleRate,
                audioChannels = channels,
                audioDurationMs = durationMs,
            )
            return TtsAudioExportResult(audioFile = destFile, timeline = timeline)
        } finally {
            // Never call tts.stop() here: Android TTS playback/synthesis is scoped to
            // the calling app, so stopping an export client can interrupt reader TTS.
            lease.close()
            if (!finished) runCatching { writer.close() }
        }
    }

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        writer: WavWriter,
        chapterTitle: String,
        timelineBuilder: TtsTimelineBuilder,
        utteranceId: String,
    ) {
        val done = CompletableDeferred<Unit>()
        val scratchFile = File.createTempFile("tts_export_", ".wav", context.cacheDir)

        fun failOnce(error: Throwable) {
            if (!done.isCompleted) done.completeExceptionally(error)
        }

        try {
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    done.complete(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    done.completeExceptionally(
                        TtsExportException("Synthesis failed (error $errorCode) for '$chapterTitle'")
                    )
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    done.completeExceptionally(
                        TtsExportException("Synthesis failed for '$chapterTitle'")
                    )
                }

                override fun onBeginSynthesis(
                    utteranceId: String?,
                    sampleRateInHz: Int,
                    audioFormat: Int,
                    channelCount: Int,
                ) {
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
                    if (audio.isEmpty()) return
                    try {
                        timelineBuilder.onAudioAvailable(audio.size)
                        writer.writePcm(audio)
                    } catch (e: Throwable) {
                        Timber.e(e, "ttsExport writing PCM failed id=$utteranceId")
                        failOnce(TtsExportException("Failed writing audio data: ${e.message}", e))
                    }
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    try {
                        timelineBuilder.onRangeStart(start, end, frame)
                    } catch (e: Throwable) {
                        Timber.e(e, "ttsExport native range capture failed id=$utteranceId")
                        failOnce(TtsExportException("Failed capturing native TTS timing: ${e.message}", e))
                    }
                }
            }
            tts.setOnUtteranceProgressListener(listener)

            // synthesizeToFile() performs synthesis/file output without playing the
            // utterance through the reader's audible TTS path. The scratch file is
            // required by the Android API; PCM is captured from onAudioAvailable().
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
                // Do not stop/cancel TTS here because that could interrupt reader TTS.
                // Let the current export request finish before releasing the pool slot,
                // otherwise another export could reuse the client while callbacks for
                // this utterance are still arriving.
                withContext(NonCancellable) {
                    runCatching { done.await() }
                }
                throw e
            }
        } finally {
            runCatching { scratchFile.delete() }
        }
    }
}

class TtsExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
