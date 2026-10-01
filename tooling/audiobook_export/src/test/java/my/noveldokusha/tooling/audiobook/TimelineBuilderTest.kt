package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineBuilderTest {
    @Test
    fun chaptersStartWherePreviousEnded() {
        val builder = TimelineBuilder()
        builder.beginChapter(chapterIndex = 0, title = "Chapter 1", novelTitle = "Novel", chapterTitle = "Chapter 1")
        builder.endIntro(actualDurationMs = 1000)
        builder.addParagraph(paragraphIndex = 0, text = "Text", actualDurationMs = 500)
        val first = builder.endChapter()

        builder.beginChapter(chapterIndex = 1, title = "Chapter 2", novelTitle = "Novel", chapterTitle = "Chapter 2")
        builder.endIntro(actualDurationMs = 800)
        builder.addParagraph(paragraphIndex = 0, text = "More", actualDurationMs = 700)
        val second = builder.endChapter()

        assertEquals(0L, first.span.startMs)
        assertEquals(1500L, first.span.endMs)
        assertEquals(1500L, second.span.startMs)
        assertEquals(3000L, second.span.endMs)
        assertEquals(3000L, builder.totalDurationMs)
    }

    @Test
    fun introPrecedesParagraphs() {
        val builder = TimelineBuilder()
        builder.beginChapter(chapterIndex = 0, title = "Chapter 1", novelTitle = "Novel", chapterTitle = "Chapter 1")
        builder.endIntro(actualDurationMs = 1200)
        builder.addParagraph(paragraphIndex = 0, text = "Body", actualDurationMs = 300)
        val chapter = builder.endChapter()

        assertEquals(chapter.span.startMs, chapter.intro.span.startMs)
        assertEquals(1200L, chapter.intro.span.endMs)
        assertEquals(1200L, chapter.paragraphs.first().span.startMs)
    }

    @Test
    fun validateTimeline_acceptsConsistentTimeline() {
        val builder = TimelineBuilder()
        builder.beginChapter(chapterIndex = 0, title = "Chapter 1", novelTitle = "Novel", chapterTitle = "Chapter 1")
        builder.endIntro(actualDurationMs = 500)
        builder.addParagraph(paragraphIndex = 0, text = "A", actualDurationMs = 500)
        builder.endChapter()
        builder.beginChapter(chapterIndex = 1, title = "Chapter 2", novelTitle = "Novel", chapterTitle = "Chapter 2")
        builder.endIntro(actualDurationMs = 500)
        builder.addParagraph(paragraphIndex = 0, text = "B", actualDurationMs = 500)
        builder.endChapter()

        validateTimeline(builder.build(), expectedTotalMs = 2000L)
    }

    @Test(expected = TimelineValidationException::class)
    fun validateTimeline_rejectsDurationMismatch() {
        val builder = TimelineBuilder()
        builder.beginChapter(chapterIndex = 0, title = "Chapter 1", novelTitle = "Novel", chapterTitle = "Chapter 1")
        builder.endIntro(actualDurationMs = 500)
        builder.addParagraph(paragraphIndex = 0, text = "A", actualDurationMs = 500)
        builder.endChapter()

        validateTimeline(builder.build(), expectedTotalMs = 1500L)
    }

    @Test(expected = IllegalStateException::class)
    fun beginChapter_failsWhenPreviousChapterIsOpen() {
        val builder = TimelineBuilder()
        builder.beginChapter(chapterIndex = 0, title = "Chapter 1", novelTitle = "Novel", chapterTitle = "Chapter 1")
        builder.beginChapter(chapterIndex = 1, title = "Chapter 2", novelTitle = "Novel", chapterTitle = "Chapter 2")
    }

    @Test
    fun chapterIntroMentionsNovelAndChapter() {
        val intro = buildChapterIntro("The Novel", "Chapter One")
        assertTrue(intro.contains("The Novel"))
        assertTrue(intro.contains("Chapter One"))
    }
}
