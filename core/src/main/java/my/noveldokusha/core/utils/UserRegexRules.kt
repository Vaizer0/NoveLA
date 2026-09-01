package my.noveldokusha.core.utils

import my.noveldokusha.core.models.RegexRule

/**
 * Общий обработчик пользовательских regex-правил очистки текста.
 *
 * Применяет [rules] к [text] последовательно, пропуская неактивные правила
 * и перехватывая исключения разбора отдельного правила (битое правило не
 * обрушивает весь конвейер). Используется и читалкой, и конвейером загрузки
 * аудио глав, чтобы итоговый текст был байт-в-байт одинаковым.
 */
fun applyUserRegexRules(text: String, rules: List<RegexRule>): String {
    var result = text
    rules.filter { it.isEnabled }.forEach { rule ->
        try {
            val regex = Regex(rule.pattern)
            result = result.replace(regex, rule.replacement)
        } catch (e: Exception) {
            println("Failed to apply user regex rule: ${e.message}, pattern: ${rule.pattern}")
        }
    }
    return result
}
