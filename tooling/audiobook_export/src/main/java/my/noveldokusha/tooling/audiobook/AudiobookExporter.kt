package my.noveldokusha.tooling.audiobook

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
 * попадает в общий WAV и в накопитель таймлайна. Никаких повторных
 * проходов и пересчётов в конце нет — прогресс и время считаются
 * инкрементально.
 *
 * MP4 переиспользует уже готовый смёрженный WAV: TTS ради MP4 повторно
 * не запускается (требование «синтезируем один раз»).
 */
class AudiobookExporter(private val context: Context) {

    suspend fun export(
        request: AudiobookExportRequest,
        chapters: List<AudiobookChapterData>,
        outputDir: File,
        onProgress: suspend (AudiobookExportProgress) -> Unit = {},
    ): AudiobookExportResult = withContext(Dispatchers.IO) {
        require(chapters.isNotEmpty()) { "no chapters to export" }
        outputDir.mkdirs()

        val tempDir = File(context.cacheDir, "$TEMP_DIR_NAME/${request.jobId()}")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        val mergedWav = File(tempDir, "merged.wav")
        val jsonFile = File(tempDir, outputBaseName(request) + ".json")
        val mediaFile = File(tempDir, outputBaseName(request) + extensionFor(request.format))

        try {
            val timeline = synthesizeAndMerge(
                request = request,
                chapters = chapters,
                tempDir = tempDir,
                mergedWav = mergedWav,
                onProgress = onProgress,
            )

            coroutineContext.ensureActive()

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.FINALIZING,
                    currentChapter = chapters.size,
                    totalChapters = chapters.size,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 96,
                    generatedAudioMs = WavAudio.durationMs(mergedWav),
                    estimatedRemainingMs = 0L,
                ),
            )

            val audioDurationMs = WavAudio.durationMs(mergedWav)
            validateTimeline(timeline, audioDurationMs)

            val visualInfo = if (request.format == AudiobookFormat.MP4) {
                val segment = prepareVisual(request, tempDir)
                try {
                    onProgress(
                        AudiobookExportProgress(
                            stage = AudiobookStage.CREATING_MP4,
                            currentChapter = chapters.size,
                            totalChapters = chapters.size,
                            chapterTitle = "",
                            currentParagraph = 0,
                            paragraphsInChapter = 0,
                            percent = 90,
                            generatedAudioMs = audioDurationMs,
                            estimatedRemainingMs = null,
                        ),
                    )
                    val mp4 = VideoLoopBuilder().build(mergedWav, segment, mediaFile)
                    if (!mp4.durationsMatch()) {
                        Timber.w(
                            "AudiobookExporter: mp4 durations differ: audio=%d video=%d",
                            mp4.audioDurationMs, mp4.videoDurationMs,
                        )
                    }
                    segment.info
                } finally {
                    segment.close()
                }
            } else {
                null
            }

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.WRITING_JSON,
                    currentChapter = chapters.size,
                    totalChapters = chapters.size,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 98,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            val sampleRateHz = mergedSampleRate ?: DEFAULT_SAMPLE_RATE
            val channels = mergedChannels ?: DEFAULT_CHANNELS

            jsonFile.writeText(
                AudiobookJsonWriter.buildDocument(
                    novelTitle = request.bookTitle,
                    novelUrl = request.bookUrl,
                    format = request.format,
                    contentMode = request.contentMode,
                    sourceLanguage = request.sourceLang,
                    targetLanguage = request.targetLang,
                    startPosition = request.startPosition,
                    endPosition = request.endPosition,
                    totalDurationMs = audioDurationMs,
                    sampleRateHz = sampleRateHz,
                    channels = channels,
                    timeline = timeline,
                    visual = visualInfo,
                ),
            )

            coroutineContext.ensureActive()

            // Промежуточный WAV живёт во временной папке, поэтому готовый
            // результат копируется в outputDir: для WAV это сам merged.wav,
            // для MP4 — уже собранный контейнер.
            val finalMedia = File(outputDir, mediaFile.name)
            val finalJson = File(outputDir, jsonFile.name)
            if (request.format == AudiobookFormat.WAV) {
                mergedWav.copyTo(finalMedia, overwrite = true)
            } else {
                mediaFile.copyTo(finalMedia, overwrite = true)
            }
            jsonFile.copyTo(finalJson, overwrite = true)

            onProgress(
                AudiobookExportProgress(
                    stage = AudiobookStage.COMPLETED,
                    currentChapter = chapters.size,
                    totalChapters = chapters.size,
                    chapterTitle = "",
                    currentParagraph = 0,
                    paragraphsInChapter = 0,
                    percent = 100,
                    generatedAudioMs = audioDurationMs,
                    estimatedRemainingMs = 0L,
                ),
            )

            tempDir.deleteRecursively()

            AudiobookExportResult(
                audioFile = finalMedia,
                jsonFile = finalJson,
                durationMs = audioDurationMs,
                chapterCount = timeline.size,
                sampleRateHz = sampleRateHz,
                channels = channels,
            )
        } catch (e: CancellationException) {
            tempDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            throw e
        }
    }

    @Volatile
    private var mergedSampleRate: Int? = null

    @Volatile
    private var mergedChannels: Int? = null

    /**
     * Синтезирует главы по порядку и на лету склеивает сегменты в общий WAV.
     *
     * Таймлайн накапливается тем же проходом: длительность каждого абзаца
     * берётся из фактически записанного файла, поэтому пересчётов после
     * экспорта не требуется.
     */
    private suspend fun synthesizeAndMerge(
        request: AudiobookExportRequest,
        chapters: List<AudiobookChapterData>,
        tempDir: File,
        mergedWav: File,
        onProgress: suspend (AudiobookExportProgress) -> Unit,
    ): List<AudiobookChapterTiming> {
        val timeline = TimelineBuilder()
        val totalTextChars = chapters.sumOf { chapter ->
            chapter.paragraphs.sumOf { it.length } + chapter.title.length
        }.coerceAtLeast(1)
        var processedChars = 0
        val startedAt = System.currentTimeMillis()

        val synthesizer = TtsAudioSynthesizer(
            context = context,
            enginePackage = request.enginePackage,
            voiceId = request.voiceId,
            speed = request.speed,
            pitch = request.pitch,
        )

        val segmentHandle = SegmentChannel(mergedWav)
        try {
            synthesizer.initialize()
            val maxChunk = synthesizer.maxChunkLength()

            chapters.forEachIndexed { chapterOffset, chapter ->
                coroutineContext.ensureActive()

                val chapterTitle = chapter.title.ifBlank { "Chapter ${chapter.position + 1}" }
                val plans = planParagraphs(splitChapterIntoParagraphs(chapter.paragraphs.joinToString("\n\n")), maxChunk)

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
                    val introDuration = synthesizeChunk(synthesizer, introText, introFile, chapterOffset, chapterTitle)
                    segmentHandle.append(introFile, introDuration)
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
                        val chunkFile = File(tempDir, "ch${chapterOffset}_p${plan.paragraphIndex}_c$chunkIndex.wav")
                        val chunkDuration = synthesizeChunk(synthesizer, chunk, chunkFile, chapterOffset, chapterTitle)
                        segmentHandle.append(chunkFile, chunkDuration)
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
                        totalChapters = chapters.size,
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
                    totalChapters = chapters.size,
                    chapterTitle = chapterTitle,
                    paragraphOffset = spokenParagraphs.size,
                    paragraphsInChapter = spokenParagraphs.size,
                    totalTextChars = totalTextChars,
                    processedChars = processedChars,
                    generatedAudioMs = timeline.totalDurationMs,
                    startedAt = startedAt,
                )
            }

            segmentHandle.finish()
        } catch (e: CancellationException) {
            segmentHandle.abort()
            throw e
        } catch (e: Exception) {
            segmentHandle.abort()
            throw e
        } finally {
            synthesizer.close()
        }

        return timeline.build()
    }

    /** Синтезирует одну порцию и возвращает её фактическую длительность. */
    private suspend fun synthesizeChunk(
        synthesizer: TtsAudioSynthesizer,
        text: String,
        target: File,
        chapterOffset: Int,
        chapterTitle: String,
    ): Long = try {
        val segment = synthesizer.synthesizeToFile(text, target)
        val format = synthesizer.currentFormat()
        if (format != null) {
            if (mergedSampleRate == null) {
                mergedSampleRate = segment.sampleRateHz
                mergedChannels = segment.channels
            }
        }
        val duration = segment.durationMs()
        if (duration <= 0L) {
            throw AudiobookChapterFailedException(
                chapterOffset + 1, chapterTitle,
                "TTS produced an empty segment for: ${text.take(40)}",
            )
        }
        duration
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
        totalTextChars: Int,
        processedChars: Int,
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

    /**
     * Держит открытым writer общего WAV, создавая его по фактическому
     * формату первого синтезированного сегмента.
     */
    private inner class SegmentChannel(private val target: File) : AutoCloseable {
        private var writer: WavAudio.StreamingWavWriter? = null

        fun append(segmentFile: File, durationMs: Long) {
            if (writer == null) {
                val segment = WavAudio.readSegment(segmentFile)
                mergedSampleRate = segment.sampleRateHz
                mergedChannels = segment.channels
                writer = WavAudio.StreamingWavWriter(
                    target = target,
                    sampleRateHz = segment.sampleRateHz,
                    channels = segment.channels,
                    bitsPerSample = segment.bitsPerSample,
                )
            }
            val active = writer ?: error("writer is not initialized")
            val segment = WavAudio.readSegment(segmentFile)
            RandomAccessFile(segmentFile, "r").use { raf -> active.append(segment, raf) }
        }

        fun finish() {
            writer?.finish()
        }

        fun abort() {
            runCatching { writer?.close() }
            writer = null
        }

        override fun close() {
            runCatching { writer?.close() }
            writer = null
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
