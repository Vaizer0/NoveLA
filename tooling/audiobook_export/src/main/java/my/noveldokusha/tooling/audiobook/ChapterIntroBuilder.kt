package my.noveldokusha.tooling.audiobook

/**
 * Детерминированная сборка вступительной реплики главы.
 *
 * Название книги и название главы произносятся в начале **каждой** главы
 * (требование фичи: глава должна быть опознаваема на слух).
 * Формулировка фиксирована, чтобы результат был предсказуемым и тестируемым.
 */
fun buildChapterIntro(
    novelTitle: String,
    chapterTitle: String,
): String {
    val novel = novelTitle.cleanSpeechText().trim()
    val chapter = chapterTitle.cleanSpeechText().trim()
    return buildString {
        if (novel.isNotEmpty()) {
            append("Novel Name: ").append(novel).append(". ")
        }
        if (chapter.isNotEmpty()) {
            append("Chapter: ").append(chapter).append(". ")
        }
    }
}

/**
 * Очистка текста перед синтезом: убирает декоративные символы вроде
 * `───`, `***`, `===`, схлопывает пробелы.
 *
 * Пустые после очистки строки отбрасываются на уровне вызывающего кода:
 * проверка «только декор» дешевле, чем синтезировать пустоту.
 */
fun String.cleanSpeechText(): String {
    val sb = StringBuilder(length)
    for (ch in this) {
        if (ch == '\n' || ch == '\r' || ch == '\t') {
            sb.append(' ')
            continue
        }
        if (ch.isLetterOrDigit() || ch.isWhitespace() || ch in SPEECH_PUNCTUATION) {
            sb.append(ch)
        }
    }
    return sb.toString().replace(WHITESPACE_RUN, " ").trim()
}

/** Строка состоит только из декоративных символов — произносить нечего. */
fun String.isDecorativeOnly(cleaned: String = cleanSpeechText()): Boolean = cleaned.isEmpty()

private val SPEECH_PUNCTUATION = charArrayOf(
    '.', ',', '!', '?', ';', ':', '-', '—', '–', '\'', '"', '(', ')',
    '[', ']', '…', '/', '&', '%', '+', '=', '#', '@', '«', '»',
)

private val WHITESPACE_RUN = Regex("\\s+")
