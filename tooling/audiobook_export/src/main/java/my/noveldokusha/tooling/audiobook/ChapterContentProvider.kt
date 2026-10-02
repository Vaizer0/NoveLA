package my.noveldokusha.tooling.audiobook

import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.text.SentenceSplitter
import my.noveldokusha.core.text.applyUserRegexRules
import my.noveldokusha.core.text.htmlToTtsParagraphs
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.feature.local_database.tables.Chapter
import org.json.JSONArray
import timber.log.Timber

/**
 * Потоковый источник глав для экспорта.
 *
 * Держит в памяти не более одной главы: тела читаются по одному запросу
 * ровно перед озвучкой. Это ключевое условие для экспорта на 1000+ глав.
 */
interface AudiobookChapterSource : AutoCloseable {
    /** Сколько глав было выбрано (включая те, что могут быть пропущены как пустые). */
    val totalChapters: Int

    /** Оценка суммарного объёма текста (в символах) — знаменатель прогресса. */
    val estimatedTotalChars: Long

    /** Следующая глава или `null`, когда главы закончились. */
    suspend fun next(): AudiobookChapterData?

    override fun close() {}
}

/**
 * Достаёт содержимое глав для озвучки.
 *
 * Текст строится тем же пайплайном, что и живое чтение (общий код в
 * `core.text`), поэтому экспорт синтезирует ровно то, что читает ридер.
 * Порядок глав задаётся исключительно `Chapter.position` (тем же, что и
 * в ридере) — алфавитная сортировка не используется нигде.
 */
class ChapterContentProvider(private val database: AppDatabase) {

    /**
     * Открывает потоковый источник глав диапазона `[startPosition, endPosition]`
     * включительно.
     *
     * @return источник в порядке чтения или `null`, если в диапазоне нет глав.
     */
    suspend fun createSource(
        bookUrl: String,
        startPosition: Int,
        endPosition: Int,
        contentMode: AudiobookContentMode,
        sourceLang: String,
        targetLang: String,
        userRegexRules: List<RegexRule> = emptyList(),
        sentenceSplittingEnabled: Boolean = false,
    ): AudiobookChapterSource? {
        val allChapters = database.chapterDao().chapters(bookUrl)
        if (allChapters.isEmpty()) return null

        val selected = allChapters
            .filter { it.position in startPosition..endPosition }
            .sortedBy { it.position }
        if (selected.isEmpty()) return null

        // Оценка объёма считается агрегатным SQL-запросом: тела глав не
        // загружаются в память только ради знаменателя прогресса.
        val estimatedChars = when (contentMode) {
            AudiobookContentMode.ORIGINAL -> database.chapterBodyDao()
                .statsInRange(bookUrl, startPosition, endPosition)
                .charCount
            AudiobookContentMode.TRANSLATION -> database.chapterTranslationDao()
                .statsInRange(bookUrl, startPosition, endPosition, sourceLang, targetLang)
                .charCount
        }

        return DatabaseAudiobookChapterSource(
            database = database,
            chapters = selected,
            contentMode = contentMode,
            sourceLang = sourceLang,
            targetLang = targetLang,
            userRegexRules = userRegexRules,
            sentenceSplittingEnabled = sentenceSplittingEnabled,
            estimatedTotalChars = estimatedChars,
        )
    }
}

/** Источник, читающий по одной главе из Room. */
private class DatabaseAudiobookChapterSource(
    private val database: AppDatabase,
    private val chapters: List<Chapter>,
    private val contentMode: AudiobookContentMode,
    private val sourceLang: String,
    private val targetLang: String,
    private val userRegexRules: List<RegexRule>,
    private val sentenceSplittingEnabled: Boolean,
    override val estimatedTotalChars: Long,
) : AudiobookChapterSource {

    private var index = 0

    override val totalChapters: Int get() = chapters.size

    override suspend fun next(): AudiobookChapterData? {
        while (index < chapters.size) {
            val chapter = chapters[index++]
            val data = when (contentMode) {
                AudiobookContentMode.ORIGINAL -> loadOriginal(chapter)
                AudiobookContentMode.TRANSLATION -> loadTranslated(chapter)
            }
            if (data != null) return data
        }
        return null
    }

    private suspend fun loadOriginal(chapter: Chapter): AudiobookChapterData? {
        val body = database.chapterBodyDao().get(chapter.url)?.body
            ?: return null
        // Идентично живому чтению: тот же HTML->абзацы пайплайн (общий в core),
        // включая пользовательские regex-правила и разбиение предложений.
        val paragraphs = htmlToTtsParagraphs(body, userRegexRules, sentenceSplittingEnabled)
        if (paragraphs.isEmpty()) {
            Timber.w("ChapterContentProvider: chapter %s has no text, skipping", chapter.url)
            return null
        }
        return AudiobookChapterData(
            position = chapter.position,
            url = chapter.url,
            title = chapter.title,
            paragraphs = paragraphs,
        )
    }

    private suspend fun loadTranslated(chapter: Chapter): AudiobookChapterData? {
        val translation = database.chapterTranslationDao()
            .getTranslations(chapter.url, sourceLang, targetLang)
            ?.takeIf { it.translatedParagraphs.isNotBlank() }
            ?: return null
        // Ридер применяет те же regex-правила к переведённому тексту перед
        // озвучкой, а при включённом разбиении предложений ещё и режет абзацы
        // тем же [SentenceSplitter]. Экспорт повторяет обе ступени.
        val withRegex = decodeTranslatedParagraphs(translation.translatedParagraphs)
            .map { applyUserRegexRules(it, userRegexRules) }
        val paragraphs = if (sentenceSplittingEnabled) {
            withRegex.flatMap { SentenceSplitter.splitParagraph(it) }
        } else {
            withRegex
        }
        if (paragraphs.isEmpty()) {
            Timber.w("ChapterContentProvider: chapter %s translation is empty, skipping", chapter.url)
            return null
        }
        return AudiobookChapterData(
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
