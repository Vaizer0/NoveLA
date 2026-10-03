package my.noveldokusha.tooling.audiobook

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Durable-состояние одного экспорта аудиокниги.
 *
 * В отличие от `cacheDir`, эта папка лежит в `filesDir` и переживает смерть
 * процесса, отмену и retry воркера. Здесь хранится:
 *
 *  - [CheckpointManifest] — атомарно записанный манифест с метаданными
 *    (формат, фаза, визуал); завершённые главы в него не входят;
 *  - `checkpoint.ndjson` — append-only журнал завершённых глав: фиксация
 *    главы стоит O(главы), а не O(всей книги), поэтому 1000+ глав не
 *    переписывают полный текст квадратично;
 *  - готовое аудио завершённых глав (`output.wav` для WAV или `audio.pcm`
 *    для MP4 — сырой PCM, из которого MP4 перекодируется при возобновлении);
 *  - производные (`audio.m4a`, `output.mp4`, `output.json`, визуал).
 *
 * Инвариант: журнал ссылается только на те байты аудио, которые уже
 * сброшены на диск (`fsync` выполняется вызывающим до [commitChapter]).
 * Поэтому оборванный на середине главы хвост никогда не считается готовым:
 * при открытии аудио обрезается до последней зафиксированной главы.
 */
class AudiobookExportJob private constructor(
    val dir: File,
    private var state: CheckpointManifest,
    val status: Status,
    /** Сколько глав пришлось отбросить из-за оборванного хвоста. */
    val droppedChapters: Int,
) {

    enum class Status { NEW, RESUMED }

    val format: AudiobookFormat = AudiobookFormat.valueOf(state.format)

    val totalChapters: Int get() = state.totalChapters
    val sampleRateHz: Int get() = state.sampleRateHz
    val channels: Int get() = state.channels
    val bitsPerSample: Int get() = state.bitsPerSample
    val frames: Long get() = state.frames
    val pcmBytes: Long get() = state.pcmBytes
    val phase: AudiobookJobPhase get() = runCatching { AudiobookJobPhase.valueOf(state.phase) }
        .getOrDefault(AudiobookJobPhase.SYNTHESIZING)
    val aacReady: Boolean get() = state.aacReady
    val mp4Ready: Boolean get() = state.mp4Ready
    val mediaDurationMs: Long get() = state.mediaDurationMs
    val completed: List<CheckpointChapter> get() = state.completed
    val completedTimings: List<AudiobookChapterTiming> get() = state.completed.map { it.timing }
    val recoveredChapters: Int get() = state.completed.size

    val audioFile: File
        get() = File(dir, if (format == AudiobookFormat.WAV) WAV_NAME else PCM_NAME)
    val aacFile: File get() = File(dir, AAC_NAME)
    val mp4File: File get() = File(dir, MP4_NAME)
    val jsonFile: File get() = File(dir, JSON_NAME)
    val visualFile: File get() = File(dir, VISUAL_NAME)
    val spoolFile: File get() = File(dir, SPOOL_NAME)
    val tempDir: File get() = File(dir, TEMP_DIR_NAME)
    val visual: CheckpointVisual? get() = state.visual

    fun setFormat(sampleRateHz: Int, channels: Int, bitsPerSample: Int) {
        if (state.sampleRateHz == sampleRateHz &&
            state.channels == channels &&
            state.bitsPerSample == bitsPerSample
        ) {
            return
        }
        state = state.copy(
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
        persist()
    }

    fun setPhase(phase: AudiobookJobPhase) {
        if (state.phase == phase.name) return
        state = state.copy(phase = phase.name)
        persist()
    }

    /** Фиксирует завершённую главу. Аудио главы обязано быть уже сброшено. */
    fun commitChapter(chapter: CheckpointChapter) {
        // Глава дописывается одной строкой в append-only журнал: стоимость
        // фиксации пропорциональна главе, а не всей книге (манифест с полным
        // текстом рос бы квадратично). Журнал fsync-ится до обновления
        // состояния в памяти.
        appendChapter(dir, chapter)
        state = state.copy(
            completed = state.completed + chapter,
            pcmBytes = state.pcmBytes + chapter.pcmBytes,
            frames = state.frames + chapter.frames,
        )
    }

    fun markAacReady(durationMs: Long = 0L) {
        state = state.copy(aacReady = true, mp4Ready = false, mediaDurationMs = durationMs)
        persist()
    }

    fun markMp4Ready(mediaDurationMs: Long) {
        state = state.copy(mp4Ready = true, mediaDurationMs = mediaDurationMs)
        persist()
    }

    fun setVisual(visual: CheckpointVisual) {
        state = state.copy(visual = visual)
        persist()
    }

    /** Удаляет всю папку job'а. Вызывается только после успешной копии в SAF. */
    fun discard() {
        runCatching { dir.deleteRecursively() }
    }

    private fun persist() = writeManifest(dir, state)

    companion object {
        const val SCHEMA_VERSION = 2

        /**
         * Версия всего пайплайна. Меняйте при изменении правил обработки
         * текста или раскладки чекпоинта, чтобы старые job'ы не смешались
         * с новыми и не воспроизвели устаревшее аудио.
         */
        const val EXPORT_PIPELINE_VERSION = 1

        internal const val DIR_NAME = "audiobook_jobs"
        private const val MANIFEST_NAME = "manifest.json"
        private const val CHECKPOINT_LOG_NAME = "checkpoint.ndjson"
        private const val WAV_NAME = "output.wav"
        private const val PCM_NAME = "audio.pcm"
        private const val AAC_NAME = "audio.m4a"
        private const val MP4_NAME = "output.mp4"
        private const val JSON_NAME = "output.json"
        private const val VISUAL_NAME = "visual.mp4"
        private const val SPOOL_NAME = "timeline.ndjson"
        private const val TEMP_DIR_NAME = "tmp"

        private const val WAV_HEADER_SIZE = 44L

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Открывает job: создаёт новый, возобновляет существующий или
         * пересоздаёт его, если чекпоинт несовместим/повреждён.
         */
        fun open(
            baseDir: File,
            request: AudiobookExportRequest,
            totalChapters: Int,
        ): AudiobookExportJob {
            val dir = File(baseDir, request.jobId())
            val fingerprint = fingerprint(request)
            val manifestFile = File(dir, MANIFEST_NAME)

            if (!manifestFile.isFile) {
                // Манифест — единственный якорь job'а: без него append-only
                // журнал мог бы «воскресить» главы чужого/устаревшего экспорта.
                dir.deleteRecursively()
                dir.mkdirs()
                val fresh = freshManifest(request, fingerprint, totalChapters)
                writeManifest(dir, fresh)
                return AudiobookExportJob(dir, fresh, Status.NEW, droppedChapters = 0)
            }

            val parsed = runCatching {
                json.decodeFromString(CheckpointManifest.serializer(), manifestFile.readText())
            }.getOrNull()

            val formatValid = parsed != null &&
                runCatching { AudiobookFormat.valueOf(parsed.format) }.isSuccess

            if (parsed == null ||
                !formatValid ||
                parsed.schemaVersion != SCHEMA_VERSION ||
                parsed.fingerprint != fingerprint ||
                parsed.totalChapters != totalChapters
            ) {
                val dropped = runCatching { readCheckpointLog(dir).chapters.size }.getOrDefault(0)
                dir.deleteRecursively()
                dir.mkdirs()
                val fresh = freshManifest(request, fingerprint, totalChapters)
                writeManifest(dir, fresh)
                return AudiobookExportJob(
                    dir = dir,
                    state = fresh,
                    status = Status.NEW,
                    droppedChapters = dropped,
                )
            }

            val log = readCheckpointLog(dir)
            val withLog = parsed.copy(
                completed = log.chapters,
                pcmBytes = log.chapters.sumOf { it.pcmBytes },
                frames = log.chapters.sumOf { it.frames },
            )
            val recovered = recoverTruncatedTail(dir, withLog, logTorn = log.torn)
            return AudiobookExportJob(
                dir = dir,
                state = recovered.manifest,
                status = Status.RESUMED,
                droppedChapters = recovered.dropped,
            )
        }

        private fun freshManifest(
            request: AudiobookExportRequest,
            fingerprint: String,
            totalChapters: Int,
        ): CheckpointManifest = CheckpointManifest(
            schemaVersion = SCHEMA_VERSION,
            fingerprint = fingerprint,
            format = request.format.name,
            totalChapters = totalChapters,
        )

        private class Recovery(val manifest: CheckpointManifest, val dropped: Int)

        /** Результат чтения append-only журнала: главы и признак оборванной строки. */
        private class CheckpointLog(val chapters: List<CheckpointChapter>, val torn: Boolean)

        /**
         * Обрезает манифест/журнал и аудиофайл до последней главы, чьи байты
         * реально записаны на диск. Всё производное от отброшенного хвоста
         * удаляется. [logTorn] заставляет перезаписать журнал, даже если
         * количество глав не изменилось: иначе оборванная строка осталась бы
         * и следующая глава дописалась бы после неё.
         */
        private fun recoverTruncatedTail(
            dir: File,
            parsed: CheckpointManifest,
            logTorn: Boolean,
        ): Recovery {
            val format = runCatching { AudiobookFormat.valueOf(parsed.format) }
                .getOrDefault(AudiobookFormat.WAV)
            val baseOffset = if (format == AudiobookFormat.WAV) WAV_HEADER_SIZE else 0L
            val audioFile = File(
                dir,
                if (format == AudiobookFormat.WAV) WAV_NAME else PCM_NAME,
            )
            val available = if (audioFile.isFile) {
                (audioFile.length() - baseOffset).coerceAtLeast(0L)
            } else {
                0L
            }

            val kept = mutableListOf<CheckpointChapter>()
            var bytes = 0L
            var frames = 0L
            var truncated = false
            parsed.completed.forEach { chapter ->
                if (!truncated && bytes + chapter.pcmBytes <= available) {
                    kept += chapter
                    bytes += chapter.pcmBytes
                    frames += chapter.frames
                } else {
                    truncated = true
                }
            }

            val dropped = parsed.completed.size - kept.size
            if (dropped == 0 && !logTorn) return Recovery(parsed, 0)

            // Производные файлы зависят от отброшенных глав — они невалидны.
            if (dropped > 0) {
                runCatching { File(dir, AAC_NAME).delete() }
                runCatching { File(dir, MP4_NAME).delete() }
                runCatching { File(dir, JSON_NAME).delete() }
            }

            val recovered = if (dropped > 0) {
                parsed.copy(
                    completed = kept,
                    pcmBytes = bytes,
                    frames = frames,
                    aacReady = false,
                    mp4Ready = false,
                    mediaDurationMs = 0L,
                    phase = AudiobookJobPhase.SYNTHESIZING.name,
                )
            } else {
                parsed.copy(completed = kept, pcmBytes = bytes, frames = frames)
            }

            if (audioFile.isFile) {
                runCatching {
                    RandomAccessFile(audioFile, "rw").use { it.setLength(baseOffset + bytes) }
                }
            }
            writeCheckpointLog(dir, kept)
            writeManifest(dir, recovered)
            return Recovery(recovered, dropped)
        }

        /** Дописывает одну главу в конец журнала и сбрасывает её на диск. */
        private fun appendChapter(dir: File, chapter: CheckpointChapter) {
            dir.mkdirs()
            val line = json.encodeToString(CheckpointChapter.serializer(), chapter) + "\n"
            FileOutputStream(File(dir, CHECKPOINT_LOG_NAME), true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }

        /** Атомарно перезаписывает журнал (после обрезки оборванного хвоста). */
        private fun writeCheckpointLog(dir: File, chapters: List<CheckpointChapter>) {
            dir.mkdirs()
            val target = File(dir, CHECKPOINT_LOG_NAME)
            val tmp = File(dir, "$CHECKPOINT_LOG_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                chapters.forEach { chapter ->
                    out.write(
                        json.encodeToString(CheckpointChapter.serializer(), chapter)
                            .toByteArray(Charsets.UTF_8),
                    )
                    out.write('\n'.code)
                }
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }

        /**
         * Читает журнал построчно. Обрыв записи возможен только в последней
         * строке, поэтому на первой нечитаемой строке чтение прекращается.
         */
        private fun readCheckpointLog(dir: File): CheckpointLog {
            val file = File(dir, CHECKPOINT_LOG_NAME)
            if (!file.isFile) return CheckpointLog(emptyList(), torn = false)
            val chapters = mutableListOf<CheckpointChapter>()
            var torn = false
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val parsed = runCatching {
                        json.decodeFromString(CheckpointChapter.serializer(), line)
                    }.getOrNull()
                    if (parsed == null) {
                        torn = true
                        break
                    }
                    chapters += parsed
                }
            }
            return CheckpointLog(chapters, torn)
        }

        private fun writeManifest(dir: File, manifest: CheckpointManifest) {
            dir.mkdirs()
            val target = File(dir, MANIFEST_NAME)
            val tmp = File(dir, "$MANIFEST_NAME.tmp")
            val bytes = json.encodeToString(CheckpointManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }

        private fun fingerprint(request: AudiobookExportRequest): String {
            val builder = StringBuilder()
            fun feed(value: Any?) {
                builder.append(value).append('\u0001')
            }
            feed(EXPORT_PIPELINE_VERSION)
            feed(request.bookUrl)
            feed(request.format)
            feed(request.contentMode)
            feed(request.sourceLang)
            feed(request.targetLang)
            feed(request.startPosition)
            feed(request.endPosition)
            feed(request.enginePackage)
            feed(request.voiceId)
            feed(java.lang.Float.floatToIntBits(request.speed))
            feed(java.lang.Float.floatToIntBits(request.pitch))
            feed(request.visualUri)
            feed(request.visualSource)
            feed(request.visualSourceName)
            feed(request.treeUri)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(builder.toString().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Фаза durable-job'а: чем именно занят экспорт сейчас. */
enum class AudiobookJobPhase { SYNTHESIZING, ENCODING, ASSEMBLING, FINALIZING }

/** Одна завершённая глава в чекпоинте. */
@Serializable
data class CheckpointChapter(
    /** Смещение главы в выбранном диапазоне (0-based). */
    val offset: Int,
    /** Номер главы, как он попадёт в `chapterIndex` таймлайна. */
    val chapterIndex: Int,
    val title: String,
    /** Сколько символов учтено в прогрессе по этой главе. */
    val chars: Long,
    /** PCM-байты этой главы (без заголовка). */
    val pcmBytes: Long,
    /** Кадры этой главы. */
    val frames: Long,
    val timing: AudiobookChapterTiming,
)

/** Persisted-визуал MP4, чтобы не пересобирать его при возобновлении. */
@Serializable
data class CheckpointVisual(
    val type: String,
    val sourceName: String,
    val loop: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val expectedDurationMs: Long,
    val frameRate: Int,
    val loopPeriodMs: Long,
    val maxSampleBytes: Int,
) {
    companion object {
        fun from(
            info: VisualSegmentInfo,
            expectedDurationMs: Long,
            frameRate: Int,
            loopPeriodMs: Long,
            maxSampleBytes: Int,
        ): CheckpointVisual = CheckpointVisual(
            type = info.type.name,
            sourceName = info.sourceName,
            loop = info.loop,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            expectedDurationMs = expectedDurationMs,
            frameRate = frameRate,
            loopPeriodMs = loopPeriodMs,
            maxSampleBytes = maxSampleBytes,
        )
    }
}

@Serializable
internal data class CheckpointManifest(
    val schemaVersion: Int,
    val fingerprint: String,
    val format: String,
    val sampleRateHz: Int = 0,
    val channels: Int = 0,
    val bitsPerSample: Int = 16,
    val totalChapters: Int,
    val pcmBytes: Long = 0L,
    val frames: Long = 0L,
    val phase: String = AudiobookJobPhase.SYNTHESIZING.name,
    val aacReady: Boolean = false,
    val mp4Ready: Boolean = false,
    val mediaDurationMs: Long = 0L,
    /**
     * Завершённые главы живут в append-only журнале (`checkpoint.ndjson`),
     * а не в манифесте: иначе манифест с полным текстом книги переписывался
     * бы на каждой главе квадратично. Поле держим для in-memory состояния.
     */
    @Transient
    val completed: List<CheckpointChapter> = emptyList(),
    val visual: CheckpointVisual? = null,
)
