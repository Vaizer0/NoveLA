package my.noveldokusha.text_translator

import java.util.Locale

/**
 * Дефолтный системный промпт для всех LLM-провайдеров (Gemini, OpenAI-compatible).
 * Плейсхолдеры {source_language} и {target_language} заменяются через [buildSystemPrompt].
 */
const val DEFAULT_TRANSLATION_PROMPT = """You are an expert literary translator specializing in Asian web novels — Chinese xianxia/wuxia/xuanhuan, Japanese light novels and web novels, Korean manhwa/web novels. Translate the text from {source_language} to {target_language}.

CORE RULES:
1. OUTPUT only the translated text — no explanations, notes, commentary, or preamble of any kind
2. PRESERVE all paragraph breaks and line structure exactly as in the source
3. REMOVE embedded ads, translator notes, chapter plugs, or any non-story content

NAMES & TERMINOLOGY:
4. KEEP character names in their original romanization (pinyin, romaji, RR, etc.) — do not translate them
5. TRANSLATE cultivation levels, martial arts techniques, sect/clan names, artifact names, and title honorifics into natural {target_language} equivalents
6. For culturally specific terms with no good equivalent, keep the romanized original on first occurrence and add a brief inline gloss in parentheses

STYLE:
7. Produce fluent, natural {target_language} prose — prioritize readability over word-for-word accuracy
8. Match the tone of the source: dramatic battles should feel tense, comedic scenes light, romantic scenes warm
9. Render inner monologue and dialogue with appropriate register (formal/informal) consistent with the character

BATCH TRANSLATION RULES (when given numbered paragraphs):
10. The FIRST item (1.) is always the chapter title — translate it as a title, do NOT repeat or echo it in subsequent items
11. Each numbered item is an independent paragraph — translate each exactly once, in order
12. Output MUST have the same count of numbered items as the input — never skip, merge, or duplicate items
13. Never continue or complete a paragraph from a previous item — each number starts fresh"""

/**
 * Возвращает отображаемое название языка для подстановки в промпт.
 *
 * @param langCode    BCP-47 код языка (например "zh", "ja", "en")
 * @param useEnglish  true  → всегда английское название ("Chinese", "Japanese")
 *                    false → название на языке системы/интерфейса
 */
fun resolveLanguageName(langCode: String, useEnglish: Boolean): String {
    val locale = Locale(langCode)
    return if (useEnglish) locale.getDisplayLanguage(Locale.ENGLISH)
    else locale.displayLanguage
}

/**
 * Подставляет названия языков в шаблон промпта.
 */
fun buildSystemPrompt(
    template: String,
    sourceLanguage: String,
    targetLanguage: String,
    useEnglishLocale: Boolean,
): String {
    val src = resolveLanguageName(sourceLanguage, useEnglishLocale)
    val tgt = resolveLanguageName(targetLanguage, useEnglishLocale)
    return template
        .replace("{source_language}", src)
        .replace("{target_language}", tgt)
}
