package my.noveldokusha.tooling.audiobook

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/** Итог успешного экспорта. */
data class AudiobookExportResult(
    val audioFile: File,
    val jsonFile: File,
    val durationMs: Long,
    val chapterCount: Int,
    val sampleRateHz: Int,
    val channels: Int,
)

/** Не удалось сгенерировать конкретную главу. */
class AudiobookChapterFailedException(
    val chapterIndex: Int,
    val chapterTitle: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Оркестратор экспорта аудиокниги.
 *
 * Синтез и мердж идут одним проходом: каждый готовый сегмент сразу
 * попадает в общий приёмник. Для WAV это потоковый RIFF-writer, для MP4 —
 * AAC-кодек, в который PCM уходит по мере синтеза. Промежуточный WAV для
 * MP4 не создаётся: нет ни лишнего файла на диске, ни второго прохода
 * декодирования/кодирования.
 *
 * Память ограничена одной главой: главы читаются из [AudiobookChapterSource]
 * по одной, таймлайн уходит в [ChapterSpool]. Это рассчитано на 1000+ глав.
 */
class AudiobookExporter(
    private val context: Context,
    private val throttle: ExportThrottle = ExportThrottle.None,
) {

    suspend fun export(
        request: AudiobookExportRequest,
        chapters: AudiobookChapterSource,
        outputDir: File,
        onProgress: suspend (AudiobookExportProgress) -> Unit = {},
    ): AudiobookExportResult = withContext(Dispatchers.IO) {
        if (chapters.totalChapters <= 0) throw IOException("no chapters to export")
        outputDir.mkdirs()
        resetMergedFormat()

        val baseName = outputBaseName(request)
        val mediaFile = File(outputDir, baseName + extensionFor(request.format))
        val jsonFile = File(outputDir, baseName + ".json")

        val tempDir = File(context.cacheDir, "$TEMP_DIR_NAME/${request.jobId()}")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        // WAV пишется сразу в outputDir — копии готового файла нет.
        // MP4 сначала получает AAC во временной папке, а контейнер собирается
        // уже в outputDir после выравнивания по фактической длительности.
        val wavTarget = if (request.format == AudiobookFormat.WAV) {
            mediaFile
        } else {
            File(tempDir, "unused.wav")
        }
        val encodedAudioTarget = File(tempDir, "audio.m4a")
        val spool = ChapterSpool(File(tempDir, "timeline.ndjson"))

        var success = false
        try {
            val outcome = synthesizeAndMerge(
                request = request,
                chapters = chapters,
                tempDir = tempDir,
                wavTarget = wavTarget,
                encodedAudioTarget = encodedAudioTarget,
                spool = spool,
                onProgress = onProgress,
            )

            coroutineContext.ensureActive()

            var audioDurationMs = outcome.durationMs

            val visualInfo = if (request.format == AudiobookFormat.MP4) {
                val segment = prepareVisual(request, tempDir)
                try {
                    onProgress(
                        AudiobookExportProgress(
                            stage = AudiobookStage.CREATING_MP4,
                            currentChapter = spool.chapterCount,
                            totalChapters = chapters.totalChapters,
                            chapterTitle = "",
                            currentParagraph = 0,
                            paragraphsInChapter = 0,
                            percent = 88,
                            generatedAudioMs = audioDurationMs,
                            estimatedRemainingMs = null,
                        ),
                    )
                    val mp4 = VideoLoopBuilder().buildFromEncodedAudio(
                        encodedAudioFile = encodedAudioTarget,
                        audioDurationMs = audioDurationMs,
                        visualSegment = segment,
                        target = mediaFile,
                    )
                    if (!mp4.durationsMatch()) {
                        Timber.w(
                            "AudiobookExporter: mp4 durations differ: audio=%d video=%d",
                            mp4.audioDurationMs, mp4.videoDurationMs,
                        )
                    }
                    // JSON и таймлайн обязаны совпасть с реальной дорожкой
                    // финального контейнера, а не с расчётом по PCM.
                    audioDurationMs = mp4.audioDurationMs.takeIf { it > 0L } ?: audioDurationMs
                    segment.info
                } finally {
                    segment.close()
                }
            } else {
                null
            }

            // Выравнивание последней главы выполняется ровно один раз —
            // после того, как фактическая длительность медиа окончательно
            // известна (для MP4 — только после сборки контейнера).
            val sealedEndMs = spool.seal(audioDurationMs)
            validateTotalDuration(sealedEndMs, audioDurationMs)

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.WRITING_JSON,
                    currentChapter = spool.chapterCount,
                    totalChapters = chapters.totalChapters,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 95,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            AudiobookJsonWriter.writeDocument(
                output = jsonFile,
                novelTitle = request.bookTitle,
                novelUrl = request.bookUrl,
                format = request.format,
                contentMode = request.contentMode,
                sourceLanguage = request.sourceLang,
                targetLanguage = request.targetLang,
                startPosition = request.startPosition,
                endPosition = request.endPosition,
                totalDurationMs = audioDurationMs,
                sampleRateHz = outcome.sampleRateHz,
                channels = outcome.channels,
                spool = spool,
                visual = visualInfo,
            )

            coroutineContext.ensureActive()

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.FINALIZING,
                    currentChapter = spool.chapterCount,
                    totalChapters = chapters.totalChapters,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 98,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.COMPLETED,
                    currentChapter = spool.chapterCount,
                    totalChapters = chapters.totalChapters,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 100,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            success = true
            AudiobookExportResult(
                audioFile = mediaFile,
                jsonFile = jsonFile,
                durationMs = audioDurationMs,
                chapterCount = spool.chapterCount,
                sampleRateHz = outcome.sampleRateHz,
                channels = outcome.channels,
            )
        } finally {
            spool.close()
            tempDir.deleteRecursively()
            if (!success) {
                // Неудачный экспорт не оставляет полуфайлов в папке пользователя.
                runCatching { mediaFile.delete() }
                runCatching { jsonFile.delete() }
            }
        }
    }

    @Volatile
    private var mergedSampleRate: Int? = null

    @Volatile
    private var mergedChannels: Int? = null

    @Volatile
    private var mergedBitsPerSample: Int? = null

    private fun resetMergedFormat() {
        mergedSampleRate = null
        mergedChannels = null
        mergedBitsPerSample = null
    }

    /** Формат уже начатого мердж-потока или `null`, пока он не определён. */
    private fun mergedFormat(): PcmFormat? {
        val rate = mergedSampleRate ?: return null
        val channels = mergedChannels ?: return null
        val bits = mergedBitsPerSample ?: return null
        return PcmFormat(rate, channels, bits)
    }

    /**
     * Синтезирует главы по порядку и на лету складывает сегменты в приёмник.
     *
     * Для WAV это потоковый RIFF-writer, для MP4 — AAC-кодек. Таймлайн
     * строится тем же проходом и сразу уходит в [spool], не накапливаясь в
     * памяти: длительность каждого абзаца берётся из фактически записанного
     * файла, поэтому пересчётов после экспорта не требуется.
     */
    private suspend fun synthesizeAndMerge(
        request: AudiobookExportRequest,
        chapters: AudiobookChapterSource,
        tempDir: File,
        wavTarget: File,
        encodedAudioTarget: File,
        spool: ChapterSpool,
        onProgress: suspend (AudiobookExportProgress) -> Unit,
    ): MergeOutcome {
        val timeline = TimelineBuilder(onChapterClosed = { spool.add(it) })
        val totalTextChars = chapters.estimatedTotalChars.coerceAtLeast(1L)
        var processedChars = 0L
        val startedAt = System.currentTimeMillis()

        // Длительность считается по накопленным кадрам, а не суммой
        // независимо округлённых миллисекунд: тогда сумма таймлайна точно
        // совпадает с длительностью смёрженного WAV даже на тысячах порций.
        var framesWritten = 0L
        var sinkDurationMs = 0L
        fun advanceFrames(segment: PcmSegment): Long {
            val rate = mergedSampleRate ?: segment.sampleRateHz
            val startMs = framesWritten * 1000L / rate
            framesWritten += segment.frameCount
            val endMs = framesWritten * 1000L / rate
            return endMs - startMs
        }

        val synthesizer = TtsAudioSynthesizer(
            context = context,
            enginePackage = request.enginePackage,
            voiceId = request.voiceId,
            speed = request.speed,
            pitch = request.pitch,
            cache = TtsSynthesisCache(context),
        )

        // MP4 кодирует AAC прямо во время синтеза; WAV пишет RIFF-поток.
        val segmentHandle: ChunkSink = if (request.format == AudiobookFormat.MP4) {
            AacChunkSink(encodedAudioTarget)
        } else {
            WavChunkSink(wavTarget)
        }
        try {
            synthesizer.initialize()
            val maxChunk = synthesizer.maxChunkLength()

            var chapterOffset = -1
            while (true) {
                coroutineContext.ensureActive()
                val chapter = chapters.next() ?: break
                chapterOffset++

                val chapterTitle = chapter.title.ifBlank { "Chapter ${chapter.position + 1}" }
                // Абзацы уже построены живым пайплайном (ChapterContentProvider),
                // повторно не разбиваем — планируем чистку и TTS-порции.
                val plans = planParagraphs(chapter.paragraphs, maxChunk)

                timeline.beginChapter(
                    chapterIndex = chapterOffset + 1,
                    title = chapterTitle,
                    novelTitle = request.bookTitle,
                    chapterTitle = chapterTitle,
                )

                // 1. Intro: название книги + название главы, произносимые в начале.
                val introText = buildChapterIntro(request.bookTitle, chapterTitle)
                if (introText.isNotBlank()) {
                    val introFile = File(tempDir, "ch${chapterOffset}_intro.wav")
                    val introSegment = synthesizeChunk(
                        synthesizer, introText, introFile, chapterOffset, chapterTitle,
                    )
                    val introDuration = advanceFrames(introSegment)
                    segmentHandle.append(introFile, introSegment, introDuration)
                    timeline.endIntro(introDuration)
                    introFile.delete()
                } else {
                    // У выбранной главы нет названия — вводим нулевой intro,
                    // чтобы таймлайн оставался строгим.
                    timeline.endIntro(MIN_SPAN_MS)
                }
                processedChars += chapterTitle.length

                // 2. Абзацы: внутренние TTS-порции складываются в один тайминг.
                val spokenParagraphs = plans.filter { !it.isEmpty }
                spokenParagraphs.forEachIndexed { paragraphOffset, plan ->
                    coroutineContext.ensureActive()

                    var paragraphDurationMs = 0L
                    plan.chunks.forEachIndexed { chunkIndex, chunk ->
                        throttle.beforeChunk()
                        val chunkFile = File(
                            tempDir,
                            "ch${chapterOffset}_p${plan.paragraphIndex}_c$chunkIndex.wav",
                        )
                        val chunkSegment = synthesizeChunk(
                            synthesizer, chunk, chunkFile, chapterOffset, chapterTitle,
                        )
                        val chunkDuration = advanceFrames(chunkSegment)
                        segmentHandle.append(chunkFile, chunkSegment, chunkDuration)
                        paragraphDurationMs += chunkDuration
                        // Сегмент удаляется сразу после присоединения.
                        runCatching { chunkFile.delete() }
                    }

                    timeline.addParagraph(
                        paragraphIndex = plan.paragraphIndex,
                        text = plan.text,
                        actualDurationMs = paragraphDurationMs,
                    )
                    processedChars += plan.text.length

                    reportProgress(
                        onProgress = onProgress,
                        stage = AudiobookStage.SYNTHESIZING,
                        chapterOffset = chapterOffset,
                        totalChapters = chapters.totalChapters,
                        chapterTitle = chapterTitle,
                        paragraphOffset = paragraphOffset,
                        paragraphsInChapter = spokenParagraphs.size,
                        totalTextChars = totalTextChars,
                        processedChars = processedChars,
                        generatedAudioMs = timeline.totalDurationMs,
                        startedAt = startedAt,
                    )
                }

                timeline.endChapter()

                // Глава завершена: intro + все абзацы озвучены. Только теперь
                // начинается следующая.
                reportProgress(
                    onProgress = onProgress,
                    stage = AudiobookStage.SYNTHESIZING,
                    chapterOffset = chapterOffset,
                    totalChapters = chapters.totalChapters,
                    chapterTitle = chapterTitle,
                    paragraphOffset = spokenParagraphs.size,
                    paragraphsInChapter = spokenParagraphs.size,
                    totalTextChars = totalTextChars,
                    processedChars = processedChars,
                    generatedAudioMs = timeline.totalDurationMs,
                    startedAt = startedAt,
                )
            }

            if (chapterOffset < 0) throw IOException("no chapters were synthesized")

            sinkDurationMs = segmentHandle.finish()
        } catch (e: CancellationException) {
            segmentHandle.abort()
            throw e
        } catch (e: Exception) {
            segmentHandle.abort()
            throw e
        } finally {
            synthesizer.close()
        }

        val rate = mergedSampleRate ?: DEFAULT_SAMPLE_RATE
        val computedMs = framesWritten * 1000L / rate
        // Для MP4 источник правды — фактическая длительность AAC-дорожки
        // (в неё входит задержка кодера), для WAV — записанные PCM-кадры.
        val durationMs = sinkDurationMs.takeIf { it > 0L } ?: computedMs
        if (durationMs <= 0L) throw IOException("synthesized audio is empty")
        return MergeOutcome(
            durationMs = durationMs,
            sampleRateHz = rate,
            channels = mergedChannels ?: DEFAULT_CHANNELS,
        )
    }

    /** Синтезирует одну порцию и возвращает её разобранный PCM-сегмент. */
    private suspend fun synthesizeChunk(
        synthesizer: TtsAudioSynthesizer,
        text: String,
        target: File,
        chapterOffset: Int,
        chapterTitle: String,
    ): PcmSegment = try {
        val segment = synthesizer.synthesizeToFile(text, target, mergedFormat())
        if (mergedSampleRate == null) {
            mergedSampleRate = segment.sampleRateHz
            mergedChannels = segment.channels
            mergedBitsPerSample = segment.bitsPerSample
        }
        if (segment.frameCount <= 0L) {
            throw AudiobookChapterFailedException(
                chapterOffset + 1, chapterTitle,
                "TTS produced an empty segment for: ${text.take(40)}",
            )
        }
        segment
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AudiobookChapterFailedException(
            chapterOffset + 1, chapterTitle,
            "Unable to synthesize chapter ${chapterOffset + 1}: ${e.message}",
            e,
        )
    }

    private suspend fun reportProgress(
        onProgress: suspend (AudiobookExportProgress) -> Unit,
        stage: AudiobookStage,
        chapterOffset: Int,
        totalChapters: Int,
        chapterTitle: String,
        paragraphOffset: Int,
        paragraphsInChapter: Int,
        totalTextChars: Long,
        processedChars: Long,
        generatedAudioMs: Long,
        startedAt: Long,
    ) {
        val ratio = (processedChars.toDouble() / totalTextChars).coerceIn(0.0, 1.0)
        val percent = (stage.weight + ratio * (MERGING_WEIGHT - stage.weight)).toInt().coerceIn(0, 99)
        val elapsedMs = System.currentTimeMillis() - startedAt
        // ETA считается по фактическому темпу синтеза, а не по числу символов.
        val remainingMs = if (ratio > 0.01) {
            ((elapsedMs / ratio) * (1.0 - ratio)).toLong()
        } else {
            null
        }
        onProgress(
            AudiobookExportProgress(
                stage = stage,
                currentChapter = chapterOffset + 1,
                totalChapters = totalChapters,
                chapterTitle = chapterTitle,
                currentParagraph = paragraphOffset + 1,
                paragraphsInChapter = paragraphsInChapter,
                percent = percent,
                generatedAudioMs = generatedAudioMs,
                estimatedRemainingMs = remainingMs,
            ),
        )
    }

    private fun prepareVisual(request: AudiobookExportRequest, tempDir: File): NormalizedVisualSegment {
        val source = request.visualSource
            ?: throw VisualProcessingException("MP4 export requires a visual source")
        val uri = request.visualUri?.let(Uri::parse)
            ?: throw VisualProcessingException("MP4 export requires a visual uri")
        return VisualSourceProcessor(context).prepare(
            source = source,
            uri = uri,
            sourceName = request.visualSourceName.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() },
        )
    }

    /** Итог синтеза: длительность и параметры получившегося аудио. */
    private data class MergeOutcome(
        val durationMs: Long,
        val sampleRateHz: Int,
        val channels: Int,
    )

    /** Приёмник синтезированных PCM-сегментов: WAV-поток или AAC-кодек. */
    private interface ChunkSink {
        fun append(segmentFile: File, segment: PcmSegment, durationMs: Long)
        /** Финализирует поток и возвращает фактическую длительность носителя. */
        fun finish(): Long
        fun abort()
    }

    /**
     * Пишет общий WAV, создавая его по фактическому формату первого
     * синтезированного сегмента.
     */
    private inner class WavChunkSink(private val target: File) : ChunkSink {
        private var writer: WavAudio.StreamingWavWriter? = null

        override fun append(segmentFile: File, segment: PcmSegment, durationMs: Long) {
            if (writer == null) {
                mergedSampleRate = segment.sampleRateHz
                mergedChannels = segment.channels
                mergedBitsPerSample = segment.bitsPerSample
                writer = WavAudio.StreamingWavWriter(
                    target = target,
                    sampleRateHz = segment.sampleRateHz,
                    channels = segment.channels,
                    bitsPerSample = segment.bitsPerSample,
                )
            }
            val active = writer ?: error("writer is not initialized")
            RandomAccessFile(segmentFile, "r").use { raf -> active.append(segment, raf) }
        }

        override fun finish(): Long {
            val active = writer ?: throw IOException("WAV writer produced no output")
            active.finish()
            return active.durationMs()
        }

        override fun abort() {
            runCatching { writer?.close() }
            writer = null
        }
    }

    /**
     * Кодирует PCM в AAC на лету, без промежуточного WAV: сегменты
     * приходят по мере синтеза, кодек и счётчик кадров живут между ними.
     */
    private inner class AacChunkSink(target: File) : ChunkSink {
        private val encoder = StreamingAacEncoder(target)

        override fun append(segmentFile: File, segment: PcmSegment, durationMs: Long) {
            encoder.append(segmentFile, segment)
        }

        override fun finish(): Long {
            encoder.finish()
            if (!encoder.isUsable()) throw IOException("AAC encoder produced no output")
            return encoder.durationMs
        }

        override fun abort() {
            encoder.abort()
        }
    }

    private companion object {
        const val TEMP_DIR_NAME = "audiobook_export"
        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_CHANNELS = 1
        const val MERGING_WEIGHT = 85
        const val MIN_SPAN_MS = 1L
    }
}

/** Стабильный идентификатор job'а для имени временной папки и уникальной работы. */
fun AudiobookExportRequest.jobId(): String = buildString {
    append(bookUrl.hashCode().toUInt().toString(16))
    append('-')
    append(format.name.lowercase())
    append('-')
    append(contentMode.name.lowercase())
    if (contentMode == AudiobookContentMode.TRANSLATION) {
        append('-').append(sourceLang).append('-').append(targetLang)
    }
    append('-').append(startPosition).append('-').append(endPosition)
}

private fun extensionFor(format: AudiobookFormat): String =
    when (format) {
        AudiobookFormat.WAV -> ".wav"
        AudiobookFormat.MP4 -> ".mp4"
    }

/**
 * Имя итогового файла: `Novel Name - Ch 1-100 [Original].wav`.
 * Невалидные символы заменяются, длина ограничивается.
 */
fun outputBaseName(request: AudiobookExportRequest): String {
    val contentLabel = if (request.contentMode == AudiobookContentMode.TRANSLATION) {
        val target = request.targetLang.ifBlank { "?" }
        "Translation-$target"
    } else {
        "Original"
    }
    val title = sanitizeFileName(request.bookTitle).ifBlank { "Novel" }
    return "$title - Ch ${request.startPosition + 1}-${request.endPosition + 1} [$contentLabel]"
}

/** Заменяет недопустимые символы и удерживает длину в пределах ФС. */
fun sanitizeFileName(name: String): String {
    val sanitized = name.replace(ILLEGAL_FILE_CHARS, "_")
        .replace(' ', ' ')
        .trim()
        .trim('.', ' ')
    return sanitized.take(MAX_FILE_NAME_LENGTH)
}

private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private const val MAX_FILE_NAME_LENGTH = 120
