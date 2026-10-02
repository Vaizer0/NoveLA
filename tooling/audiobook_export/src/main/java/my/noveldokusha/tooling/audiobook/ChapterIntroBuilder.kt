package my.noveldokusha.tooling.audiobook

import my.noveldokusha.text_to_speech.cleanTextForTts

/**
 * Детерминированная сборка вступительной реплики главы.
 *
 * Название книги и название главы произносятся в начале **каждой** главы
 * (требование фичи: глава должна быть опознаваема на слух).
 * Формулировка фиксирована, чтобы результат был предсказуемым и тестируемым.
 *
 * Названия чистятся теми же правилами, что применяет живой TTS
 * ([cleanTextForTts]) — отдельные export-specific regex-правила не нужны.
 */
fun buildChapterIntro(
    novelTitle: String,
    chapterTitle: String,
): String {
    val novel = cleanTextForTts(novelTitle).trim()
    val chapter = cleanTextForTts(chapterTitle).trim()
    return buildString {
        if (novel.isNotEmpty()) {
            append("Novel Name: ").append(novel).append(". ")
        }
        if (chapter.isNotEmpty()) {
            append("Chapter: ").append(chapter).append(". ")
        }
    }
}
