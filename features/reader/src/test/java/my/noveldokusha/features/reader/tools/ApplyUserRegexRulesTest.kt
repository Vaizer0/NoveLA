package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.text.applyUserRegexRules
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplyUserRegexRulesTest {

    private fun wholeWords(pattern: String, replacement: String = ""): RegexRule =
        RegexRule(pattern = pattern, replacement = replacement, wholeWordsOnly = true)

    @Test
    fun `cyrillic standalone words are replaced but substrings are kept`() {
        val result = applyUserRegexRules(
            text = "да был и не да, давно",
            rules = listOf(wholeWords("да", "было"))
        )
        assertEquals("было был и не было, давно", result)
    }

    @Test
    fun `latin prefix and suffix matches are skipped`() {
        val result = applyUserRegexRules(
            text = "cat category concat cat.",
            rules = listOf(wholeWords("cat", "X"))
        )
        assertEquals("X category concat X.", result)
    }

    @Test
    fun `digits are word characters`() {
        val result = applyUserRegexRules(
            text = "42 142 4242",
            rules = listOf(wholeWords("42", "X"))
        )
        assertEquals("X 142 4242", result)
    }

    @Test
    fun `underscore counts as a word character`() {
        val result = applyUserRegexRules(
            text = "x_ab ab ab_c",
            rules = listOf(wholeWords("ab", "X"))
        )
        assertEquals("x_ab X ab_c", result)
    }

    @Test
    fun `alternation with capture group keeps group reference in replacement`() {
        val result = applyUserRegexRules(
            text = "кот и собака и котлета",
            rules = listOf(wholeWords("(кот|собака)", "[$1]"))
        )
        assertEquals("[кот] и [собака] и котлета", result)
    }

    @Test
    fun `disabled rule is skipped`() {
        val rule = RegexRule(
            pattern = "да",
            replacement = "было",
            isEnabled = false,
            wholeWordsOnly = true
        )
        assertEquals("да был и не да", applyUserRegexRules("да был и не да", listOf(rule)))
    }

    @Test
    fun `wholeWordsOnly false keeps plain substring behavior`() {
        val rule = RegexRule(pattern = "да", replacement = "было", wholeWordsOnly = false)
        assertEquals("было был и не было", applyUserRegexRules("да был и не да", listOf(rule)))
    }

    @Test
    fun `global and per novel rules are applied in order`() {
        // Порядок списков повторяет AppPreferences.effectiveRegexRules:
        // глобальные правила идут первыми, затем персональные для новеллы.
        val globalRules = listOf(
            RegexRule(pattern = "да", replacement = "G", wholeWordsOnly = false)
        )
        val novelRules = listOf(
            RegexRule(pattern = "да", replacement = "N", wholeWordsOnly = true)
        )

        val result = applyUserRegexRules(
            text = "да был и не да",
            rules = globalRules + novelRules
        )

        // Глобальное правило без wholeWordsOnly заменяет ОБА вхождения "да" на "G"
        // ещё до запуска персонального правила, поэтому к его применению в тексте
        // не остаётся ни одного standalone "да" — и правило с флагом ничего не находит.
        // Ожидаемый результат: "G был и не G" (а не "G был и не N").
        assertEquals("G был и не G", result)
    }

    @Test
    fun `pattern with own anchors works inside the wrapper`() {
        val result = applyUserRegexRules(
            text = "Глава 1",
            rules = listOf(wholeWords("^Глава", "Chapter"))
        )
        assertEquals("Chapter 1", result)
    }
}
