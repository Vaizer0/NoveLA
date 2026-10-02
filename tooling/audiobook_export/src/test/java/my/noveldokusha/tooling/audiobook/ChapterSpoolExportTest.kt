package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterSpoolExportTest {

    private fun spoolWithTwoChapters(spool: ChapterSpool) {
        val builder = TimelineBuilder(onChapterClosed = { spool.add(it) })
        builder.beginChapter(chapterIndex = 1, title = "C1", novelTitle = "N", chapterTitle = "C1")
        builder.endIntro(actualDurationMs = 1_000)
        builder.addParagraph(paragraphIndex = 0, text = "a", actualDurationMs = 500)
        builder.endChapter()

        builder.beginChapter(chapterIndex = 2, title = "C2", novelTitle = "N", chapterTitle = "C2")
        builder.endIntro(actualDurationMs = 1_000)
        builder.addParagraph(paragraphIndex = 0, text = "b", actualDurationMs = 500)
        builder.endChapter()
    }

    @Test
    fun sealAlignsOnlyTheLastChapter() {
        val file = File.createTempFile("spool", ".ndjson")
        try {
            val spool = ChapterSpool(file)
            spoolWithTwoChapters(spool)
            assertEquals(2, spool.chapterCount)

            val sealedEnd = spool.seal(totalMs = 3_200)
            assertEquals(3_200L, sealedEnd)

            val lines = file.readLines().filter { it.isNotEmpty() }
            assertEquals(2, lines.size)
            // Первая глава не изменилась: 0..1500.
            assertTrue(lines[0].contains("\"chapterIndex\":1"))
            assertTrue(lines[0].contains("\"endMs\":1500"))
            // Последняя подтянута к фактической длительности 3200.
            assertTrue(lines[1].contains("\"chapterIndex\":2"))
            assertTrue(lines[1].contains("\"endMs\":3200"))
            spool.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun writeDocumentStreamsEveryChapter() {
        val spoolFile = File.createTempFile("spool", ".ndjson")
        val output = File.createTempFile("document", ".json")
        try {
            val spool = ChapterSpool(spoolFile)
            spoolWithTwoChapters(spool)
            val sealedEnd = spool.seal(totalMs = 3_200)

            AudiobookJsonWriter.writeDocument(
                output = output,
                novelTitle = "Novel",
                novelUrl = "https://example.com/novel",
                format = AudiobookFormat.WAV,
                contentMode = AudiobookContentMode.ORIGINAL,
                sourceLanguage = "en",
                targetLanguage = null,
                startPosition = 0,
                endPosition = 1,
                totalDurationMs = sealedEnd,
                sampleRateHz = 22_050,
                channels = 1,
                spool = spool,
                visual = null,
            )

            val text = output.readText()
            assertTrue(text.contains("\"schemaVersion\":1"))
            assertTrue(text.contains("\"count\":2"))
            assertTrue(text.contains("\"timeline\":["))
            assertTrue(text.contains("\"chapterIndex\":1"))
            assertTrue(text.contains("\"chapterIndex\":2"))
            assertTrue(text.contains("\"durationMs\":3200"))
            assertTrue(text.endsWith("]}"))
            spool.close()
        } finally {
            spoolFile.delete()
            output.delete()
        }
    }
}
