package my.noveldokusha.text_to_speech

/**
 * Single source of truth for the text normalization live TTS applies before
 * synthesis. The audiobook export reuses these functions so it synthesizes the
 * exact same spoken text as online reading.
 */

private const val DECORATIVE_CHARS = """\-=*_~+#·•°─-┿"""
private val SEPARATOR_ONLY = Regex("""^\s*[$DECORATIVE_CHARS]{3,}\s*$""")
private val LEADING_DECORATIVE = Regex("""^[$DECORATIVE_CHARS]{3,}\s*""")
private val TRAILING_DECORATIVE = Regex("""\s*[$DECORATIVE_CHARS]{3,}$""")

/** True when [text] is blank or consists only of decorative separator lines. */
fun isOnlyDecorators(text: String): Boolean {
    if (text.isBlank()) return true
    return text.lines().all { line ->
        line.isBlank() || SEPARATOR_ONLY.matches(line)
    }
}

/**
 * The exact cleanup live TTS applies before synthesis: strip leading/trailing
 * decorative runs on every line and trim.
 */
fun cleanTextForTts(text: String): String = text.lines().joinToString("\n") { line ->
    line.replace(LEADING_DECORATIVE, "")
        .replace(TRAILING_DECORATIVE, "")
        .trim()
}

/** Length of the leading decorative run on the first line, used for TTS offsets. */
fun leadingDecorativeOffset(text: String): Int =
    text.lines().firstOrNull()?.let { LEADING_DECORATIVE.find(it)?.value?.length } ?: 0
