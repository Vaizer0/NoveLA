package my.noveldokusha.text_to_speech

import my.noveldokusha.core.models.RegexRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Парити-тест конвейера подготовки текста главы к синтезу.
 *
 * Гарантирует, что TtsTextPreparer.paragraphsFromBody разбивает тело главы
 * теми же правилами, что используются в читалке (buildBodyItems):
 *  1. стрип HTML + замена `<`/`>` + схлопывание пробелов + \r\n → \n;
 *  2. применение пользовательских regex-правил (applyUserRegexRules);
 *  3. разбиение на логические абзацы (длинные параграфы, уважающие скобки/кавычки);
 *  4. очистка декоративных разделителей (параллельно ReaderTextToSpeech).
 */
class TtsTextPreparerParityTest {

    @Test
    fun `paragraphs match the same cleanBody pipeline as the reader`() {
        val body = """
            <p>   First   paragraph.
            </p>

            <p>   Second <b>one</b>.   </p>
        """.trimIndent()

        val paragraphs = TtsTextPreparer.paragraphsFromBody(body)

        // HTML теги удалены, а пробелы схлопнуты идентично buildBodyItems.
        assertEquals(listOf("First paragraph.", "Second one."), paragraphs)
    }

    @Test
    fun `user regex rules are applied before paragraph splitting`() {
        val rules = listOf(
            RegexRule(
                pattern = "REPLACE_ME",
                replacement = "replaced",
                isEnabled = true,
            )
        )
        val body = "One REPLACE_ME\n\nTwo"
        val paragraphs = TtsTextPreparer.paragraphsFromBody(body, rules)
        assertEquals(listOf("One replaced", "Two"), paragraphs)
    }

    @Test
    fun `blank regex rules do not crash the pipeline`() {
        val rules = listOf(
            RegexRule(
                pattern = "[invalid",
                replacement = "",
                isEnabled = true,
            )
        )
        // Битое правило не должно валить обработку (как в applyUserRegexRules).
        val paragraphs = TtsTextPreparer.paragraphsFromBody("Hello", rules)
        assertEquals(listOf("Hello"), paragraphs)
    }

    @Test
    fun `decorator only paragraphs are skipped by isOnlyDecorators and cleaned`() {
        assertTrue(TtsTextPreparer.isOnlyDecorators("---\n***\n==="))
        assertFalse(TtsTextPreparer.isOnlyDecorators("Real text"))
        assertEquals("Text", TtsTextPreparer.cleanForTts("---Text"))
    }

    @Test
    fun `long paragraphs are split respecting logical blocks`() {
        val longPara = "Word ".repeat(500)
        val paragraphs = TtsTextPreparer.paragraphsFromBody(longPara)
        assertTrue("long paragraph should be split into chunks", paragraphs.size > 1)
        assertTrue(paragraphs.all { it.contains("Word") })
    }

    @Test
    fun `chunkIntoUtterances keeps chunks within limit`() {
        val text = "Sentence one. Sentence two. Sentence three. " +
            "Sentence four. Sentence five. "
        val maxLen = 40
        val chunks = TtsTextPreparer.chunkIntoUtterances(text, maxLen)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= maxLen })
        assertEquals(
            text.replace(" ", "").replace(".", ""),
            chunks.joinToString("") { it.replace(" ", "").replace(".", "") }
        )
    }
}
