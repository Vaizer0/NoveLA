package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты char-офсетной карты [TtsTextPreparer.cleanForTtsWithMap]: переводят
 * координаты очищенного текста в координаты ТОЧНО отображаемого текста — так,
 * как это требуется видеорежимом (корректировка #2 плана).
 */
class CleanForTtsWithMapTest {

    @Test
    fun `cleanForTtsWithMap matches cleanForTts output`() {
        val src = "---   Hello   world   ~~~"
        val result = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals(TtsTextPreparer.cleanForTts(src), result.cleaned)
    }

    @Test
    fun `leading decorators on single line map to real text`() {
        val src = "---Hello world"
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals("Hello world", cleaned)
        // "H" в display-тексте стоит справа от трёх "-"
        assertEquals(3, map[0])
        assertEquals(4, map[1])
    }

    @Test
    fun `multi-line decorators on line 2 map correctly`() {
        val src = "First line\n---Second line"
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals(listOf("First line", "Second line"), cleaned.lines())
        // Первая строка без изменений: индекс в display совпадает.
        val firstLineLen = "First line".length
        assertEquals(0, map[0])
        // 'S' (первый символ второй строки) стоит после "First line\n" + 3 декоратора
        val expected = firstLineLen + 1 + 3
        assertEquals(expected, map[firstLineLen])
    }

    @Test
    fun `regex replacement changing string length still maps correctly`() {
        // Пользовательское правило в paragraphsFromBody меняет длину; но cleanForTts
        // само по себе не применяет regex — проверяем чистую карту на изменённом тексте.
        val src = "Hello world of text"
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals(src, cleaned)
        assertTrue(map.indices.all { map[it] == it })
    }

    @Test
    fun `trailing decorators removed and do not produce map entries`() {
        val src = "Some text ~~~~~"
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals("Some text", cleaned)
        assertEquals("Some text".length, map.size)
    }

    @Test
    fun `empty map for blank text`() {
        val (_, map) = TtsTextPreparer.cleanForTtsWithMap("   ")
        assertEquals(0, map.size)
    }

    @Test
    fun `chunk boundaries accumulate offsets across paragraphs`() {
        // Проверяем, что карты абзацев независимы и корректны.
        val p1 = TtsTextPreparer.cleanForTtsWithMap("---Alpha")
        assertEquals("Alpha", p1.cleaned)
        assertEquals(3, p1.map[0])
        val p2 = TtsTextPreparer.cleanForTtsWithMap("Beta---")
        assertEquals("Beta", p2.cleaned)
        assertEquals(0, p2.map[0])
    }

    @Test
    fun `translated text with special chars maps identity`() {
        val src = "Привет, мир! Это тест."
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals(src, cleaned)
        assertTrue(map.indices.all { map[it] == it })
    }

    @Test
    fun `multi-line text maps chars across the newline correctly`() {
        // cleanForTts сохраняет перевод строки; сам разделитель получает identity
        // в карте, чтобы границы кусков, совпадающие с \n, тоже маппились верно.
        val src = "line one\ncontinued"
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals("line one\ncontinued", cleaned)
        assertEquals(cleaned.length, map.size)
        val nl = cleaned.indexOf('\n')
        assertEquals(nl, src.indexOf('\n'))
        // Перевод строки в display-тексте стоит на той же позиции, что в cleaned.
        assertEquals(nl, map[nl])
        // Первый символ второй строки ('c') стоит сразу после '\n' в display.
        assertEquals(nl + 1, map[nl + 1])
        // Последний символ второй строки отображается корректно.
        assertEquals(cleaned.lastIndex, map[cleaned.lastIndex])
    }

    @Test
    fun `all cleaned chars map inside display text`() {
        val src = "  --  Some    spaced  -- text  "
        val (cleaned, map) = TtsTextPreparer.cleanForTtsWithMap(src)
        assertEquals(cleaned.length, map.size)
        for ((i, c) in cleaned.withIndex()) {
            assertTrue("cleaned[$i]=$c must map into src", map[i] in src.indices)
            assertEquals(c, src[map[i]])
        }
    }
}
