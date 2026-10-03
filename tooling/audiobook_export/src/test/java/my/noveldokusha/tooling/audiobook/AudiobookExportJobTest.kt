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
}
