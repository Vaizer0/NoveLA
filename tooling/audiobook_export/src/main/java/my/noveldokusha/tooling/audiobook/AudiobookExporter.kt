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
 * Ошибка, после которой retry заведомо не поможет (битый запрос, недоступный
 * SAF, отсутствующий визуал). Воркер помечает такую задачу failed, а не
 * гоняет её по кругу.
 */
class PermanentAudiobookExportException(
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
 * Синтез идемпотентно возобновляем: каждая завершённая глава фиксируется в
 * [AudiobookExportJob] (atomic manifest + аудио в `filesDir`) и при retry,
 * смерти процесса или отмене продолжается с последней целой главы, не
 * переозвучивая готовое.
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
        onProgress: suspend (AudiobookExportProgress) -> Unit = {},
    ): AudiobookExportResult = withContext(Dispatchers.IO) {
        if (chapters.totalChapters <= 0) throw PermanentAudiobookExportException("no chapters to export")

        val job = AudiobookExportJob.open(
            baseDir = File(context.filesDir, AudiobookExportJob.DIR_NAME),
            request = request,
            totalChapters = chapters.totalChapters,
        )
        Timber.i(
            "AudiobookExport: %s job=%s chapters=%d format=%s recovered=%d dropped=%d",
            if (job.status == AudiobookExportJob.Status.NEW) "NEW" else "RESUMING",
            job.dir.name,
            job.totalChapters,
            job.format,
            job.recoveredChapters,
            job.droppedChapters,
        )

        val tempDir = job.tempDir
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        resetMergedFormat()
        if (job.sampleRateHz > 0) {
            mergedSampleRate = job.sampleRateHz
            mergedChannels = job.channels
            mergedBitsPerSample = job.bitsPerSample
        }

        val spool = ChapterSpool(job.spoolFile)
        try {
            // Все главы уже синтезированы (обрыв на этапе сборки/JSON): TTS не
            // трогаем, только завершаем носитель и пересобираем производные.
            val outcome = if (job.recoveredChapters >= job.totalChapters) {
                restoreCompletedIntoSpool(job, spool)
                finalizeSynthesizedAudio(request, job)
            } else {
                synthesizeAndMerge(request, chapters, job, spool, onProgress)
            }

            coroutineContext.ensureActive()
            var audioDurationMs = outcome.durationMs

            val visualInfo = if (request.format == AudiobookFormat.MP4) {
                val segment = visualFor(request, job)
                onProgress(
                    AudiobookExportProgress(
                        stage = AudiobookStage.CREATING_MP4,
                        currentChapter = spool.chapterCount,
                        totalChapters = job.totalChapters,
                        chapterTitle = "",
                        currentParagraph = 0,
                        paragraphsInChapter = 0,
                        percent = 88,
                        generatedAudioMs = audioDurationMs,
                        estimatedRemainingMs = null,
                    ),
                )
                val mp4 = VideoLoopBuilder().buildFromEncodedAudio(
                    encodedAudioFile = job.aacFile,
                    audioDurationMs = audioDurationMs,
                    visualSegment = segment,
                    target = job.mp4File,
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
                job.markMp4Ready(audioDurationMs)
                segment.info
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
                    totalChapters = job.totalChapters,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 95,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            AudiobookJsonWriter.writeDocument(
                output = job.jsonFile,
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

            job.setPhase(AudiobookJobPhase.FINALIZING)
            coroutineContext.ensureActive()

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.FINALIZING,
                    currentChapter = spool.chapterCount,
                    totalChapters = job.totalChapters,
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
                    totalChapters = job.totalChapters,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 100,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            AudiobookExportResult(
                audioFile = if (request.format == AudiobookFormat.WAV) job.audioFile else job.mp4File,
                jsonFile = job.jsonFile,
                durationMs = audioDurationMs,
                chapterCount = spool.chapterCount,
                sampleRateHz = outcome.sampleRateHz,
                channels = outcome.channels,
            )
        } finally {
            spool.close()
            tempDir.deleteRecursively()
            // Durable-состояние job'а НЕ удаляется: очистка выполняется
            // воркером только после успешной копии в SAF.
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
     * Синтезирует оставшиеся главы по порядку и на лету складывает сегменты
     * в durable-приёмник, а по завершении каждой главы фиксирует чекпоинт.
     */
    private suspend fun synthesizeAndMerge(
        request: AudiobookExportRequest,
        chapters: AudiobookChapterSource,
        job: AudiobookExportJob,
        spool: ChapterSpool,
        onProgress: suspend (AudiobookExportProgress) -> Unit,
    ): MergeOutcome {
        val timeline = TimelineBuilder(onChapterClosed = { spool.add(it) })
        // Готовые главы возвращаются в таймлайн (и в spool), но TTS для них
        // не вызывается: их аудио уже лежит в durable-накопителе.
        timeline.restore(job.completedTimings)
        repeat(job.recoveredChapters) {
            if (chapters.next() == null) {
                throw IOException(
                    "checkpoint has ${job.recoveredChapters} chapters but the source ended early",
                )
            }
        }

        val totalTextChars = chapters.estimatedTotalChars.coerceAtLeast(1L)
        var processedChars = job.completed.sumOf { it.chars }
        val startedAt = System.currentTimeMillis()

        // Длительность считается по накопленным кадрам, а не суммой
        // независимо округлённых миллисекунд: тогда сумма таймлайна точно
        // совпадает с длительностью смёрженного WAV даже на тысячах порций.
        var framesWritten = job.frames
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

        val resume = job.recoveredChapters > 0 || job.pcmBytes > 0L
        val durable = createDurableStore(job, resume)
        try {
            synthesizer.initialize()
            val maxChunk = synthesizer.maxChunkLength()

            var chapterOffset = job.recoveredChapters - 1
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

                var chapterBytes = 0L
                var chapterFrames = 0L
                var chapterChars = 0L

                // 1. Intro: название книги + название главы, произносимые в начале.
                val introText = buildChapterIntro(request.bookTitle, chapterTitle)
                if (introText.isNotBlank()) {
                    val introFile = File(job.tempDir, "ch${chapterOffset}_intro.wav")
                    val introSegment = synthesizeChunk(
                        synthesizer, introText, introFile, chapterOffset, chapterTitle, job,
                    )
                    val introDuration = advanceFrames(introSegment)
                    durable.append(introFile, introSegment)
                    chapterBytes += introSegment.dataLength
                    chapterFrames += introSegment.frameCount
                    timeline.endIntro(introDuration)
                    introFile.delete()
                } else {
                    // У выбранной главы нет названия — вводим нулевой intro,
                    // чтобы таймлайн оставался строгим.
                    timeline.endIntro(MIN_SPAN_MS)
                }
                chapterChars += chapterTitle.length
                processedChars += chapterTitle.length

                // 2. Абзацы: внутренние TTS-порции складываются в один тайминг.
                val spokenParagraphs = plans.filter { !it.isEmpty }
                spokenParagraphs.forEachIndexed { paragraphOffset, plan ->
                    coroutineContext.ensureActive()

                    var paragraphDurationMs = 0L
                    plan.chunks.forEachIndexed { chunkIndex, chunk ->
                        throttle.beforeChunk()
                        val chunkFile = File(
                            job.tempDir,
                            "ch${chapterOffset}_p${plan.paragraphIndex}_c$chunkIndex.wav",
                        )
                        val chunkSegment = synthesizeChunk(
                            synthesizer, chunk, chunkFile, chapterOffset, chapterTitle, job,
                        )
                        val chunkDuration = advanceFrames(chunkSegment)
                        durable.append(chunkFile, chunkSegment)
                        chapterBytes += chunkSegment.dataLength
                        chapterFrames += chunkSegment.frameCount
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
                    chapterChars += plan.text.length

                    reportProgress(
                        onProgress = onProgress,
                        stage = AudiobookStage.SYNTHESIZING,
                        chapterOffset = chapterOffset,
                        totalChapters = job.totalChapters,
                        chapterTitle = chapterTitle,
                        paragraphOffset = paragraphOffset,
                        paragraphsInChapter = spokenParagraphs.size,
                        totalTextChars = totalTextChars,
                        processedChars = processedChars,
                        generatedAudioMs = timeline.totalDurationMs,
                        startedAt = startedAt,
                    )
                }

                val timing = timeline.endChapter()

                // Аудио главы обязано оказаться на диске раньше, чем манифест
                // назовёт главу готовой: иначе оборванный хвост считался бы валидным.
                durable.sync()
                job.commitChapter(
                    CheckpointChapter(
                        offset = chapterOffset,
                        chapterIndex = chapterOffset + 1,
                        title = chapterTitle,
                        chars = chapterChars,
                        pcmBytes = chapterBytes,
                        frames = chapterFrames,
                        timing = timing,
                    ),
                )

                // Глава завершена: intro + все абзацы озвучены. Только теперь
                // начинается следующая.
                reportProgress(
                    onProgress = onProgress,
                    stage = AudiobookStage.SYNTHESIZING,
                    chapterOffset = chapterOffset,
                    totalChapters = job.totalChapters,
                    chapterTitle = chapterTitle,
                    paragraphOffset = spokenParagraphs.size,
                    paragraphsInChapter = spokenParagraphs.size,
                    totalTextChars = totalTextChars,
                    processedChars = processedChars,
                    generatedAudioMs = timeline.totalDurationMs,
                    startedAt = startedAt,
                )
            }

            if (chapterOffset + 1 != job.totalChapters) {
                throw IOException(
                    "synthesized ${chapterOffset + 1} of ${job.totalChapters} chapters",
                )
            }

            val sinkDurationMs = durable.finish()
            val rate = mergedSampleRate ?: job.sampleRateHz.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
            val channels = mergedChannels ?: job.channels.takeIf { it > 0 } ?: DEFAULT_CHANNELS
            val bits = mergedBitsPerSample ?: job.bitsPerSample.takeIf { it > 0 } ?: DEFAULT_BITS_PER_SAMPLE
            job.setFormat(rate, channels, bits)
            if (request.format == AudiobookFormat.MP4) {
                job.markAacReady(sinkDurationMs)
            }

            val computedMs = framesWritten * 1000L / rate
            // Для MP4 источник правды — фактическая длительность AAC-дорожки
            // (в неё входит задержка кодера), для WAV — записанные PCM-кадры.
            val durationMs = sinkDurationMs.takeIf { it > 0L } ?: computedMs
            if (durationMs <= 0L) throw IOException("synthesized audio is empty")
            return MergeOutcome(durationMs, rate, channels)
        } catch (e: CancellationException) {
            durable.abort()
            throw e
        } catch (e: Exception) {
            durable.abort()
            throw e
        } finally {
            synthesizer.close()
        }
    }

    /**
     * Завершает носитель для job'а, у которого уже синтезированы все главы
     * (например, процесс умер на этапе сборки MP4 или записи JSON).
     */
    private fun finalizeSynthesizedAudio(
        request: AudiobookExportRequest,
        job: AudiobookExportJob,
    ): MergeOutcome {
        val rate = job.sampleRateHz
        val channels = job.channels
        val bits = job.bitsPerSample
        if (rate <= 0 || channels <= 0) {
            throw IOException("checkpoint has all chapters but no audio format")
        }
        return when (request.format) {
            AudiobookFormat.WAV -> {
                WavAudio.StreamingWavWriter(
                    target = job.audioFile,
                    sampleRateHz = rate,
                    channels = channels,
                    bitsPerSample = bits,
                    resumeDataBytes = job.pcmBytes,
                ).use { it.finish() }
                MergeOutcome(WavAudio.durationMs(job.audioFile), rate, channels)
            }
            AudiobookFormat.MP4 -> {
                val stored = job.mediaDurationMs
                val duration = if (job.aacReady && stored > 0L &&
                    job.aacFile.isFile && job.aacFile.length() > 0L
                ) {
                    stored
                } else {
                    encodePcmToAac(job)
                }
                MergeOutcome(duration, rate, channels)
            }
        }
    }

    /** Перекодирует durable PCM в AAC (например, при возобновлении MP4). */
    private fun encodePcmToAac(job: AudiobookExportJob): Long {
        val rate = job.sampleRateHz
        val channels = job.channels
        val bits = job.bitsPerSample
        if (rate <= 0 || job.pcmBytes <= 0L) throw IOException("checkpoint has no PCM audio")
        runCatching { job.aacFile.delete() }
        val encoder = StreamingAacEncoder(job.aacFile)
        return try {
            encoder.append(
                job.audioFile,
                PcmSegment(rate, channels, bits, dataOffset = 0L, dataLength = job.pcmBytes),
            )
            encoder.finish()
            if (!encoder.isUsable()) throw IOException("AAC encoder produced no output")
            val duration = encoder.durationMs
            job.markAacReady(duration)
            duration
        } catch (e: Exception) {
            encoder.abort()
            throw e
        }
    }

    /** Синтезирует одну порцию и возвращает её разобранный PCM-сегмент. */
    private suspend fun synthesizeChunk(
        synthesizer: TtsAudioSynthesizer,
        text: String,
        target: File,
        chapterOffset: Int,
        chapterTitle: String,
        job: AudiobookExportJob,
    ): PcmSegment = try {
        val segment = synthesizer.synthesizeToFile(text, target, mergedFormat())
        if (mergedSampleRate == null) {
            mergedSampleRate = segment.sampleRateHz
            mergedChannels = segment.channels
            mergedBitsPerSample = segment.bitsPerSample
            // Фиксируем формат сразу, чтобы чекпоинт был возобновляем и в
            // случае обрыва до конца первой главы.
            job.setFormat(segment.sampleRateHz, segment.channels, segment.bitsPerSample)
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

    /**
     * Возвращает визуальный сегмент: сохранённый в job'е или заново
     * подготовленный и сразу сохранённый, чтобы возобновление не пересобирало
     * визуал повторно.
     */
    private fun visualFor(
        request: AudiobookExportRequest,
        job: AudiobookExportJob,
    ): NormalizedVisualSegment {
        val persisted = job.visual
        if (persisted != null && job.visualFile.isFile && job.visualFile.length() > 0L) {
            val type = runCatching { VisualSource.valueOf(persisted.type) }.getOrNull()
            if (type != null) {
                return NormalizedVisualSegment(
                    file = job.visualFile,
                    info = VisualSegmentInfo(
                        type = type,
                        sourceName = persisted.sourceName,
                        loop = persisted.loop,
                        durationMs = persisted.durationMs,
                        width = persisted.width,
                        height = persisted.height,
                    ),
                    expectedDurationMs = persisted.expectedDurationMs,
                    frameRate = persisted.frameRate,
                    loopPeriodMs = persisted.loopPeriodMs,
                    maxSampleBytes = persisted.maxSampleBytes,
                )
            }
        }

        val prepared = prepareVisual(request)
        val target = job.visualFile
        runCatching { target.delete() }
        prepared.file.copyTo(target, overwrite = true)
        prepared.file.delete()
        job.setVisual(
            CheckpointVisual.from(
                info = prepared.info,
                expectedDurationMs = prepared.expectedDurationMs,
                frameRate = prepared.frameRate,
                loopPeriodMs = prepared.loopPeriodMs,
                maxSampleBytes = prepared.maxSampleBytes,
            ),
        )
        // Файл теперь живёт в job'е: закрывать сегмент нельзя — close()
        // удалил бы уже durable-копию.
        return prepared.copy(file = target)
    }

    private fun prepareVisual(request: AudiobookExportRequest): NormalizedVisualSegment {
        val source = request.visualSource
            ?: throw PermanentAudiobookExportException("MP4 export requires a visual source")
        val uri = request.visualUri?.let(Uri::parse)
            ?: throw PermanentAudiobookExportException("MP4 export requires a visual uri")
        return VisualSourceProcessor(context).prepare(
            source = source,
            uri = uri,
            sourceName = request.visualSourceName.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() },
        )
    }

    private fun createDurableStore(job: AudiobookExportJob, resume: Boolean): DurableStore {
        val known = mergedFormat()
        return when (job.format) {
            AudiobookFormat.WAV -> WavDurableStore(
                job = job,
                knownFormat = known,
                resumeDataBytes = if (resume) job.pcmBytes else -1L,
            )
            AudiobookFormat.MP4 -> Mp4DurableStore(job = job, resume = resume, knownFormat = known)
        }
    }

    /** Повторно раскладывает готовые главы из чекпоинта в spool таймлайна. */
    private fun restoreCompletedIntoSpool(job: AudiobookExportJob, spool: ChapterSpool) {
        val timeline = TimelineBuilder(onChapterClosed = { spool.add(it) })
        timeline.restore(job.completedTimings)
    }

    /** Итог синтеза: длительность и параметры получившегося аудио. */
    private data class MergeOutcome(
        val durationMs: Long,
        val sampleRateHz: Int,
        val channels: Int,
    )

    /**
     * Durable-приёмник синтезированных PCM-сегментов. Для WAV пишет прямо в
     * `output.wav` (с резервированием заголовка), для MP4 — в `audio.pcm` и
     * параллельно в AAC-кодек.
     */
    private interface DurableStore : AutoCloseable {
        fun append(segmentFile: File, segment: PcmSegment)
        /** Сбрасывает PCM на диск перед фиксацией главы в чекпоинте. */
        fun sync()
        /** Финализирует поток и возвращает фактическую длительность носителя. */
        fun finish(): Long
        fun abort()
    }

    private inner class WavDurableStore(
        private val job: AudiobookExportJob,
        knownFormat: PcmFormat?,
        private val resumeDataBytes: Long,
    ) : DurableStore {
        private var writer: WavAudio.StreamingWavWriter? = null

        init {
            if (knownFormat != null) {
                writer = WavAudio.StreamingWavWriter(
                    target = job.audioFile,
                    sampleRateHz = knownFormat.sampleRateHz,
                    channels = knownFormat.channels,
                    bitsPerSample = knownFormat.bitsPerSample,
                    resumeDataBytes = resumeDataBytes,
                )
            }
        }

        private fun writerFor(segment: PcmSegment): WavAudio.StreamingWavWriter {
            writer?.let { return it }
            val created = WavAudio.StreamingWavWriter(
                target = job.audioFile,
                sampleRateHz = segment.sampleRateHz,
                channels = segment.channels,
                bitsPerSample = segment.bitsPerSample,
                resumeDataBytes = resumeDataBytes,
            )
            writer = created
            return created
        }

        override fun append(segmentFile: File, segment: PcmSegment) {
            val active = writerFor(segment)
            RandomAccessFile(segmentFile, "r").use { active.append(segment, it) }
        }

        override fun sync() {
            writer?.sync()
        }

        override fun finish(): Long {
            val active = writer ?: throw IOException("WAV writer produced no output")
            active.finish()
            val duration = active.durationMs()
            active.close()
            writer = null
            return duration
        }

        override fun abort() {
            runCatching { writer?.close() }
            writer = null
        }

        override fun close() {
            runCatching { writer?.close() }
            writer = null
        }
    }

    /**
     * Пишет PCM в durable `audio.pcm` и параллельно кодирует AAC. При
     * возобновлении обрезает PCM до последней зафиксированной главы и
     * заново переигрывает её в свежий кодек — синтез при этом не повторяется.
     */
    private inner class Mp4DurableStore(
        private val job: AudiobookExportJob,
        resume: Boolean,
        knownFormat: PcmFormat?,
    ) : DurableStore {
        private val pcmFile: File = job.audioFile
        private var raf: RandomAccessFile? = null
        private var encoder: StreamingAacEncoder? = null

        init {
            if (resume) {
                val format = knownFormat ?: throw IOException("resume without known PCM format")
                RandomAccessFile(pcmFile, "rw").use { it.setLength(job.pcmBytes) }
                if (job.pcmBytes > 0L) {
                    val active = StreamingAacEncoder(job.aacFile)
                    encoder = active
                    active.append(
                        pcmFile,
                        PcmSegment(
                            sampleRateHz = format.sampleRateHz,
                            channels = format.channels,
                            bitsPerSample = format.bitsPerSample,
                            dataOffset = 0L,
                            dataLength = job.pcmBytes,
                        ),
                    )
                } else {
                    runCatching { job.aacFile.delete() }
                }
            } else {
                runCatching { job.aacFile.delete() }
            }
            raf = RandomAccessFile(pcmFile, "rw").apply { seek(length()) }
        }

        override fun append(segmentFile: File, segment: PcmSegment) {
            val active = raf ?: error("durable store is closed")
            RandomAccessFile(segmentFile, "r").use { source ->
                source.seek(segment.dataOffset)
                var remaining = segment.dataLength
                val buffer = ByteArray(COPY_BUFFER)
                while (remaining > 0L) {
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = source.read(buffer, 0, toRead)
                    if (read <= 0) break
                    active.write(buffer, 0, read)
                    remaining -= read
                }
            }
            val encoderActive = encoder ?: StreamingAacEncoder(job.aacFile).also { encoder = it }
            encoderActive.append(segmentFile, segment)
        }

        override fun sync() {
            raf?.fd?.sync()
        }

        override fun finish(): Long {
            raf?.close()
            raf = null
            val encoderActive = encoder ?: throw IOException("AAC encoder produced no output")
            encoderActive.finish()
            encoder = null
            if (!encoderActive.isUsable()) throw IOException("AAC encoder produced no output")
            return encoderActive.durationMs
        }

        override fun abort() {
            runCatching { raf?.close() }
            raf = null
            encoder?.abort()
            encoder = null
        }

        override fun close() {
            abort()
        }
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_BITS_PER_SAMPLE = 16
        const val MERGING_WEIGHT = 85
        const val MIN_SPAN_MS = 1L
        const val COPY_BUFFER = 256 * 1024
    }
}

/** Удаляет durable-состояние завершённого job'а. Вызывать только после копии в SAF. */
fun discardAudiobookExport(context: Context, request: AudiobookExportRequest) {
    val jobDir = File(
        File(context.filesDir, AudiobookExportJob.DIR_NAME),
        request.jobId(),
    )
    runCatching { jobDir.deleteRecursively() }
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
