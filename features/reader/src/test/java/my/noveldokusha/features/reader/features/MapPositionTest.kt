package my.noveldokusha.features.reader.features

import my.noveldokusha.features.reader.domain.ImgEntry
import my.noveldokusha.features.reader.domain.ReaderItem
import my.noveldokusha.core.text.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты mapPosition: перенос сохранённой позиции чтения между гранулярностями
 * «абзац» и «предложение» по абзацному списку главы.
 *
 * Фикстура (абзацная нумерация, позиции 1..4):
 *   1 — длинный абзац (3 предложения), 2 — картинка, 3 — короткий абзац (1 блок),
 *   4 — длинный абзац (3 предложения).
 * Сплит-нумерация: 1..3 — первый абзац, 4 — картинка, 5 — короткий,
 * 6..8 — последний абзац.
 */
class MapPositionTest {

    private val longParagraph = "He walked through the quiet streets of the old town, watching the lights flicker on in the windows of the houses he passed one by one. The sun was setting slowly behind the distant hills, painting the whole sky in warm shades of orange and pink. Birds sang their evening songs from the treetops, filling the quiet air with gentle and soothing melodies."
    private val shortParagraph = "He walked home. The sun was setting."

    private val structure: List<ReaderItem> = listOf(
        body(position = 1, text = longParagraph),
        image(position = 2),
        body(position = 3, text = shortParagraph),
        body(position = 4, text = longParagraph),
    )

    private fun body(position: Int, text: String) = ReaderItem.Body(
        chapterUrl = "chapter",
        chapterIndex = 0,
        chapterItemPosition = position,
        text = text,
        location = ReaderItem.Location.MIDDLE,
    )

    private fun image(position: Int) = ReaderItem.Image(
        chapterUrl = "chapter",
        chapterIndex = 0,
        chapterItemPosition = position,
        location = ReaderItem.Location.MIDDLE,
        text = "<img src=\"cover.png\" yrel=\"1.45\">",
        image = ImgEntry(path = "cover.png", yrel = 1.45f),
    )

    // Опоры фикстуры: без них ожидаемые позиции ниже недостоверны.
    @Test
    fun `fixture splits as expected`() {
        assertEquals(3, SentenceSplitter.splitParagraph(longParagraph).size)
        assertEquals(1, SentenceSplitter.splitParagraph(shortParagraph).size)
    }

    @Test
    fun `title position stays zero`() {
        assertEquals(0, mapPosition(structure, savedWithSplit = false, currentSplit = true, savedPos = 0))
        assertEquals(0, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 0))
    }

    @Test
    fun `matching granularity keeps the position`() {
        assertEquals(3, mapPosition(structure, savedWithSplit = false, currentSplit = false, savedPos = 3))
        assertEquals(7, mapPosition(structure, savedWithSplit = true, currentSplit = true, savedPos = 7))
    }

    @Test
    fun `paragraph position maps to the first piece of the split paragraph`() {
        // Абзац 1 → его первое предложение.
        assertEquals(1, mapPosition(structure, savedWithSplit = false, currentSplit = true, savedPos = 1))
        // Картинка занимает один слот в обеих гранулярностях.
        assertEquals(4, mapPosition(structure, savedWithSplit = false, currentSplit = true, savedPos = 2))
        // Короткий абзац не режется.
        assertEquals(5, mapPosition(structure, savedWithSplit = false, currentSplit = true, savedPos = 3))
        // Последний абзац → первое из трёх предложений.
        assertEquals(6, mapPosition(structure, savedWithSplit = false, currentSplit = true, savedPos = 4))
    }

    @Test
    fun `split position maps back to its paragraph`() {
        // Любое из трёх предложений последнего абзаца → абзац 4.
        assertEquals(4, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 6))
        assertEquals(4, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 7))
        assertEquals(4, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 8))
        // Картинка → картинка, короткий абзац → тот же абзац.
        assertEquals(2, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 4))
        assertEquals(3, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 5))
        // Первое предложение первого абзаца → абзац 1.
        assertEquals(1, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 1))
    }

    @Test
    fun `unknown structure falls back to the saved position`() {
        assertEquals(4, mapPosition(emptyList(), savedWithSplit = false, currentSplit = true, savedPos = 4))
    }

    @Test
    fun `out of range position falls back to the saved position`() {
        assertEquals(99, mapPosition(structure, savedWithSplit = false, currentSplit = true, savedPos = 99))
        assertEquals(9, mapPosition(structure, savedWithSplit = true, currentSplit = false, savedPos = 9))
    }
}
