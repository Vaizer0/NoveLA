package my.noveldokusha.tooling.audiobook

import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.feature.local_database.tables.Chapter
import org.json.JSONArray
import timber.log.Timber

/**
 * Достаёт содержимое глав для озвучки.
 *
 * Порядок глав задаётся исключительно `Chapter.position` (тем же, что и
 * в ридере) — алфавитная сортировка не используется нигде.
 */
class ChapterContentProvider(private val database: AppDatabase) {

    /**
     * Возвращает главы диапазона `[startPosition, endPosition]` включительно,
     * уже разбитые на абзацы.
     *
     * @return список в порядке чтения; пустой, если в диапазоне нет скачанных глав.
     */
    suspend fun loadChapters(
        bookUrl: String,
        startPosition: Int,
        endPosition: Int,
        contentMode: AudiobookContentMode,
        sourceLang: String,
        targetLang: String,
    ): List<AudiobookChapterData> {
        val allChapters = database.chapterDao().chapters(bookUrl)
        if (allChapters.isEmpty()) return emptyList()

        val selected = allChapters
            .filter { it.position in startPosition..endPosition }
            .sortedBy { it.position }

        if (selected.isEmpty()) return emptyList()

        return when (contentMode) {
            AudiobookContentMode.ORIGINAL -> loadOriginal(selected)
            AudiobookContentMode.TRANSLATION -> loadTranslated(selected, sourceLang, targetLang)
        }
    }

    private suspend fun loadOriginal(chapters: List<Chapter>): List<AudiobookChapterData> {
        val bodies = database.chapterBodyDao()
            .getBodiesByUrls(chapters.map { it.url })
            .associate { it.url to it.body }

        return chapters.mapNotNull { chapter ->
            val body = bodies[chapter.url] ?: return@mapNotNull null
            val paragraphs = splitChapterIntoParagraphs(stripHtml(body))
            if (paragraphs.isEmpty()) {
                Timber.w("ChapterContentProvider: chapter %s has no text, skipping", chapter.url)
                return@mapNotNull null
            }
            AudiobookChapterData(
                position = chapter.position,
                url = chapter.url,
                title = chapter.title,
                paragraphs = paragraphs,
            )
        }
    }

    private suspend fun loadTranslated(
        chapters: List<Chapter>,
        sourceLang: String,
        targetLang: String,
    ): List<AudiobookChapterData> {
        val translations = database.chapterTranslationDao()
            .getTranslationsByChapterUrls(chapters.map { it.url }, sourceLang, targetLang)
            .filter { it.translatedParagraphs.isNotBlank() }
            .associateBy { it.chapterUrl }

        return chapters.mapNotNull { chapter ->
            val translation = translations[chapter.url] ?: return@mapNotNull null
            val paragraphs = decodeTranslatedParagraphs(translation.translatedParagraphs)
            if (paragraphs.isEmpty()) {
                Timber.w("ChapterContentProvider: chapter %s translation is empty, skipping", chapter.url)
                return@mapNotNull null
            }
            AudiobookChapterData(
                position = chapter.position,
                url = chapter.url,
                title = translation.titleTranslation.ifBlank { chapter.title },
                paragraphs = paragraphs,
            )
        }
    }

    /** Разбирает сохранённый JSON-массив переведённых абзацев. */
    private fun decodeTranslatedParagraphs(json: String): List<String> = try {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val text = array.optString(index).trim()
                if (text.isNotEmpty()) add(text)
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "ChapterContentProvider: cannot decode translated paragraphs")
        emptyList()
    }

    /**
     * Убирает HTML-разметку из тела главы.
     *
     * Абзацные границы задаются тегами `<p>`/`<br>`, иначе текст склеится
     * в одну строку и таймлайн абзацев окажется бессмысленным.
     */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return html
        val withBreaks = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n\n")
            .replace(Regex("(?i)<p\\s*[^>]*>"), "\n\n")
            .replace(Regex("(?i)</div\\s*>"), "\n\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
        val text = TAG_REGEX.replace(withBreaks, "")
        return unescapeEntities(text)
    }

    private fun unescapeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    private companion object {
        val TAG_REGEX = Regex("<[^>]+>")
    }
}
