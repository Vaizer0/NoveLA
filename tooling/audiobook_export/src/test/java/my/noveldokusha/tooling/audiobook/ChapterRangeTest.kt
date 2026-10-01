package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterRangeTest {
    @Test(expected = InvalidChapterRangeException::class)
    fun validateChapterRange_throwsWhenNoChapters() {
        validateChapterRange(startPosition = 0, endPosition = -1, firstAvailablePosition = 5, lastAvailablePosition = 4)
    }

    @Test(expected = InvalidChapterRangeException::class)
    fun validateChapterRange_throwsWhenStartBeforeFirst() {
        validateChapterRange(startPosition = 0, endPosition = 1, firstAvailablePosition = 1, lastAvailablePosition = 3)
    }

    @Test(expected = InvalidChapterRangeException::class)
    fun validateChapterRange_throwsWhenEndAfterLast() {
        validateChapterRange(startPosition = 2, endPosition = 5, firstAvailablePosition = 1, lastAvailablePosition = 3)
    }

    @Test(expected = InvalidChapterRangeException::class)
    fun validateChapterRange_throwsWhenStartAfterEnd() {
        validateChapterRange(startPosition = 2, endPosition = 1, firstAvailablePosition = 0, lastAvailablePosition = 3)
    }

    @Test
    fun validateChapterRange_returnsValidRange() {
        val range = validateChapterRange(startPosition = 2, endPosition = 3, firstAvailablePosition = 0, lastAvailablePosition = 5)
        assertEquals(2, range.startPosition)
        assertEquals(3, range.endPosition)
        assertEquals(2, range.count)
        assertEquals(3, range.displayStart)
        assertEquals(4, range.displayEnd)
    }

    @Test
    fun availableExportPositions_filtersAndSorts() {
        val result = availableExportPositions(
            allPositions = listOf(5, 1, 2, 3, 2),
            downloadedPositions = setOf(2, 3, 10),
        )
        assertEquals(listOf(2, 3), result)
    }
}
