package my.noveldokusha.tooling.audiobook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class AudiobookExportJobTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "novela-job-test-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun request(
        format: AudiobookFormat = AudiobookFormat.MP4,
        treeUri: String = "content://tree/1",
    ): AudiobookExportRequest = AudiobookExportRequest(
        bookUrl = "book://1",
        bookTitle = "Novel",
        format = format,
        contentMode = AudiobookContentMode.ORIGINAL,
        sourceLang = "",
        targetLang = "",
        startPosition = 0,
        endPosition = 9,
        enginePackage = "engine",
        voiceId = "voice",
        speed = 1f,
        pitch = 1f,
        visualUri = null,
        visualSource = null,
        visualSourceName = null,
        treeUri = treeUri,
    )

    private fun timing(index: Int, startMs: Long, endMs: Long): AudiobookChapterTiming {
        val introEnd = startMs + 100L
        return AudiobookChapterTiming(
            chapterIndex = index,
            title = "Chapter $index",
            span = AudiobookSpan(startMs, endMs, endMs - startMs),
            intro = AudiobookIntroTiming(
                span = AudiobookSpan(startMs, introEnd, introEnd - startMs),
                novelTitle = "Novel",
                chapterTitle = "Chapter $index",
            ),
            paragraphs = listOf(
                AudiobookParagraphTiming(
                    paragraphIndex = 0,
                    span = AudiobookSpan(introEnd, endMs, endMs - introEnd),
                    text = "text",
                ),
            ),
        )
    }

    private fun chapter(
        offset: Int,
        pcmBytes: Long,
        frames: Long,
        startMs: Long,
        endMs: Long,
    ): CheckpointChapter = CheckpointChapter(
        offset = offset,
        chapterIndex = offset + 1,
        title = "Chapter ${offset + 1}",
        chars = 100L,
        pcmBytes = pcmBytes,
        frames = frames,
        timing = timing(offset + 1, startMs, endMs),
    )

    private fun appendBytes(file: File, count: Int) {
        file.parentFile?.mkdirs()
        file.appendBytes(ByteArray(count))
    }

    private fun commitChapters(
        job: AudiobookExportJob,
        count: Int,
        startIndex: Int,
        bytesPerChapter: Int,
        framesPerChapter: Long,
    ) {
        repeat(count) { i ->
            val index = startIndex + i
            appendBytes(job.audioFile, bytesPerChapter)
            job.commitChapter(
                chapter(
                    offset = index,
                    pcmBytes = bytesPerChapter.toLong(),
                    frames = framesPerChapter,
                    startMs = index * 1000L,
                    endMs = (index + 1) * 1000L,
                ),
            )
        }
    }

    @Test
    fun newJobIsCreatedEmpty() {
        val job = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(AudiobookExportJob.Status.NEW, job.status)
        assertEquals(0, job.recoveredChapters)
        assertTrue(job.completedTimings.isEmpty())
        assertTrue(job.dir.isDirectory)
    }

    @Test
    fun resumeRecoversCommittedChaptersWithoutSynthesizing() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        first.setFormat(24000, 1, 16)
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(0, pcmBytes = 1000, frames = 24000, startMs = 0, endMs = 1000))
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(1, pcmBytes = 1000, frames = 24000, startMs = 1000, endMs = 2000))

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(AudiobookExportJob.Status.RESUMED, resumed.status)
        assertEquals(2, resumed.recoveredChapters)
        assertEquals(2000L, resumed.pcmBytes)
        assertEquals(48000L, resumed.frames)
        assertEquals(listOf(1, 2), resumed.completedTimings.map { it.chapterIndex })
        // Ровно то, что нужно, чтобы пропустить уже готовые главы при синтезе.
        assertEquals(2, resumed.completed.size)
    }

    @Test
    fun fingerprintMismatchStartsFresh() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        appendBytes(first.audioFile, 500)
        first.commitChapter(chapter(0, pcmBytes = 500, frames = 12000, startMs = 0, endMs = 500))

        val other = AudiobookExportJob.open(
            baseDir,
            request(treeUri = "content://tree/other"),
            totalChapters = 10,
        )

        assertEquals(AudiobookExportJob.Status.NEW, other.status)
        assertEquals(0, other.recoveredChapters)
    }

    @Test
    fun totalChaptersMismatchStartsFresh() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        appendBytes(first.audioFile, 500)
        first.commitChapter(chapter(0, pcmBytes = 500, frames = 12000, startMs = 0, endMs = 500))

        val other = AudiobookExportJob.open(baseDir, request(), totalChapters = 11)

        assertEquals(AudiobookExportJob.Status.NEW, other.status)
        assertEquals(0, other.recoveredChapters)
    }

    @Test
    fun corruptManifestStartsFresh() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        File(first.dir, "manifest.json").writeText("this is not json")
        appendBytes(first.audioFile, 500)

        val other = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(AudiobookExportJob.Status.NEW, other.status)
        assertEquals(0, other.recoveredChapters)
    }

    @Test
    fun checkpointAheadOfDiskDropsTrailingChapter() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(0, pcmBytes = 1000, frames = 24000, startMs = 0, endMs = 1000))
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(1, pcmBytes = 1000, frames = 24000, startMs = 1000, endMs = 2000))
        // Имитируем обрыв: манифест говорит про 2 главы, а на диск легло меньше.
        RandomAccessFile(first.audioFile, "rw").use { it.setLength(1500) }

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(AudiobookExportJob.Status.RESUMED, resumed.status)
        assertEquals(1, resumed.recoveredChapters)
        assertEquals(1, resumed.droppedChapters)
        assertEquals(1000L, resumed.pcmBytes)
        assertEquals(24000L, resumed.frames)
        assertEquals(1000L, resumed.audioFile.length())
    }

    @Test
    fun droppedTailResetsDerivedMarkersAndDeletesFiles() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(0, pcmBytes = 1000, frames = 24000, startMs = 0, endMs = 1000))
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(1, pcmBytes = 1000, frames = 24000, startMs = 1000, endMs = 2000))
        first.setPhase(AudiobookJobPhase.ASSEMBLING)
        first.markAacReady()
        first.markMp4Ready(mediaDurationMs = 2000)
        first.aacFile.writeBytes(byteArrayOf(1))
        first.mp4File.writeBytes(byteArrayOf(1))
        first.jsonFile.writeBytes(byteArrayOf(1))

        RandomAccessFile(first.audioFile, "rw").use { it.setLength(1200) }

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(1, resumed.droppedChapters)
        assertEquals(AudiobookJobPhase.SYNTHESIZING, resumed.phase)
        assertFalse(resumed.aacReady)
        assertFalse(resumed.mp4Ready)
        assertEquals(0L, resumed.mediaDurationMs)
        assertFalse(resumed.aacFile.exists())
        assertFalse(resumed.mp4File.exists())
        assertFalse(resumed.jsonFile.exists())
    }

    @Test
    fun discardRemovesWholeJob() {
        val job = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        assertTrue(job.dir.isDirectory)

        job.discard()

        assertFalse(job.dir.exists())
    }

    @Test
    fun resumesLargeExportAfterInterruptedSynthesis() {
        val total = 60
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = total)
        first.setFormat(24000, 1, 16)
        // Синтез оборвался на 45-й главе: дальше манифест не двигался.
        commitChapters(first, count = 45, startIndex = 0, bytesPerChapter = 1000, framesPerChapter = 24000)

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = total)

        assertEquals(AudiobookExportJob.Status.RESUMED, resumed.status)
        assertEquals(45, resumed.recoveredChapters)
        assertEquals(45000L, resumed.pcmBytes)
        assertEquals(1_080_000L, resumed.frames)
        // Продолжаем ровно оставшиеся главы и снова переживаем перезапуск.
        commitChapters(resumed, count = total - 45, startIndex = 45, bytesPerChapter = 1000, framesPerChapter = 24000)

        val finished = AudiobookExportJob.open(baseDir, request(), totalChapters = total)
        assertEquals(total, finished.recoveredChapters)
        assertEquals(0, finished.droppedChapters)
        assertEquals(60000L, finished.pcmBytes)
        assertEquals((1..total).toList(), finished.completedTimings.map { it.chapterIndex })
    }

    @Test
    fun recoversFromInterruptedMergeAtScale() {
        val total = 60
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = total)
        first.setFormat(24000, 1, 16)
        commitChapters(first, count = total, startIndex = 0, bytesPerChapter = 1000, framesPerChapter = 24000)
        // Обрыв во время сборки: манифест помнит 60 глав, а на диске половина 60-й.
        RandomAccessFile(first.audioFile, "rw").use { it.setLength(59 * 1000L + 500L) }

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = total)

        assertEquals(59, resumed.recoveredChapters)
        assertEquals(1, resumed.droppedChapters)
        assertEquals(59000L, resumed.pcmBytes)
        assertEquals(59000L, resumed.audioFile.length())
        // Повторно фиксируем последнюю главу после пересборки её хвоста.
        appendBytes(resumed.audioFile, 1000)
        resumed.commitChapter(
            chapter(59, pcmBytes = 1000, frames = 24000, startMs = 59000, endMs = 60000),
        )

        val finished = AudiobookExportJob.open(baseDir, request(), totalChapters = total)
        assertEquals(total, finished.recoveredChapters)
        assertEquals(0, finished.droppedChapters)
    }

    @Test
    fun aacDurationAndVisualSurviveResume() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        first.setFormat(24000, 1, 16)
        commitChapters(first, count = 3, startIndex = 0, bytesPerChapter = 1000, framesPerChapter = 24000)
        first.setPhase(AudiobookJobPhase.ENCODING)
        first.markAacReady(durationMs = 12_345L)
        first.setVisual(
            CheckpointVisual(
                type = VisualSource.IMAGE.name,
                sourceName = "cover",
                loop = true,
                durationMs = 500,
                width = 1280,
                height = 720,
                expectedDurationMs = 500,
                frameRate = 30,
                loopPeriodMs = 1000,
                maxSampleBytes = 4096,
            ),
        )

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(3, resumed.recoveredChapters)
        assertEquals(AudiobookJobPhase.ENCODING, resumed.phase)
        assertTrue(resumed.aacReady)
        assertFalse(resumed.mp4Ready)
        assertEquals(12_345L, resumed.mediaDurationMs)
        assertEquals("cover", resumed.visual?.sourceName)
        assertEquals(1280, resumed.visual?.width)
    }

    @Test
    fun wavOffsetIsRespectedDuringRecovery() {
        val wavRequest = request(format = AudiobookFormat.WAV)
        val first = AudiobookExportJob.open(baseDir, wavRequest, totalChapters = 10)
        appendBytes(first.audioFile, 44)
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(0, pcmBytes = 1000, frames = 24000, startMs = 0, endMs = 1000))
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(1, pcmBytes = 1000, frames = 24000, startMs = 1000, endMs = 2000))

        val resumed = AudiobookExportJob.open(baseDir, wavRequest, totalChapters = 10)

        assertEquals(2, resumed.recoveredChapters)
        assertEquals(2000L, resumed.pcmBytes)
        // 44-байтовый RIFF-заголовок не считается PCM.
        assertEquals(2044L, resumed.audioFile.length())
    }

    @Test
    fun completedChaptersLiveInLogNotInManifest() {
        val job = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        appendBytes(job.audioFile, 1000)
        job.commitChapter(chapter(0, pcmBytes = 1000, frames = 24000, startMs = 0, endMs = 1000))

        // Манифест не должен нести полный текст книги (иначе рост O(n^2)).
        val manifest = File(job.dir, "manifest.json").readText()
        assertFalse(manifest.contains("\"completed\""))
        assertFalse(manifest.contains("\"paragraphs\""))

        val log = File(job.dir, "checkpoint.ndjson").readText()
        assertTrue(log.contains("\"paragraphs\""))
    }

    @Test
    fun tornCheckpointLogLineIsDroppedAndDoesNotBlockNextChapter() {
        val first = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        appendBytes(first.audioFile, 1000)
        first.commitChapter(chapter(0, pcmBytes = 1000, frames = 24000, startMs = 0, endMs = 1000))
        // Имитируем kill во время append: последняя строка журнала оборвана.
        File(first.dir, "checkpoint.ndjson").appendText("{\"offset\":1,")

        val resumed = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)

        assertEquals(1, resumed.recoveredChapters)
        assertEquals(0, resumed.droppedChapters)
        // Оборванная строка вычищена: следующая фиксация за ней не «застревает».
        appendBytes(resumed.audioFile, 1000)
        resumed.commitChapter(chapter(1, pcmBytes = 1000, frames = 24000, startMs = 1000, endMs = 2000))

        val finished = AudiobookExportJob.open(baseDir, request(), totalChapters = 10)
        assertEquals(2, finished.recoveredChapters)
        assertEquals(0, finished.droppedChapters)
    }
}
