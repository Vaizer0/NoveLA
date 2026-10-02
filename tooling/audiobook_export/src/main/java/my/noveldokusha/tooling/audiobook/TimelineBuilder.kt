package my.noveldokusha.tooling.audiobook

/** Тайминг одного фрагмента главы (intro или абзац). */
data class AudiobookSpan(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
)

/** Абзац в таймлайне. Только абзацный уровень — без пословных таймингов. */
data class AudiobookParagraphTiming(
    val paragraphIndex: Int,
    val span: AudiobookSpan,
    val text: String,
)

/** Вступительная реплика главы: произнесённые название книги и главы. */
data class AudiobookIntroTiming(
    val span: AudiobookSpan,
    val novelTitle: String,
    val chapterTitle: String,
)

/** Глава в таймлайне. */
data class AudiobookChapterTiming(
    val chapterIndex: Int,
    val title: String,
    val span: AudiobookSpan,
    val intro: AudiobookIntroTiming,
    val paragraphs: List<AudiobookParagraphTiming>,
)

/**
 * Накопитель таймлайна.
 *
 * Единственный источник правды о времени — фактическая длительность
 * синтезированного аудио ([advance]), переданная экспортером. Никаких
 * оценок по числу символов: глава N начинается ровно там, где закончилась
 * глава N-1, без искусственных пауз.
 *
 * Класс намеренно не потокобезопасен — им пользуется один поток синтеза.
 */
class TimelineBuilder {
    private val chapters = mutableListOf<AudiobookChapterTiming>()

    private var currentMs: Long = 0

    private var openChapterIndex: Int? = null
    private var openChapterTitle: String = ""
    private var pendingIntroText: String? = null
    private var pendingIntroNovel: String = ""
    private var pendingIntroChapter: String = ""
    private var pendingParagraphs: MutableList<AudiobookParagraphTiming> = mutableListOf()
    private var pendingIntroSpan: AudiobookSpan? = null

    /** Общая длительность накопленного аудио в мс. */
    val totalDurationMs: Long get() = currentMs

    /** Количество уже полностью закрытых глав. */
    val chapterCount: Int get() = chapters.size

    /**
     * Открывает новую главу. Предыдущая должна быть закрыта [endChapter] —
     * иначе часть аудио попала бы в таймлайн дважды.
     */
    fun beginChapter(chapterIndex: Int, title: String, novelTitle: String, chapterTitle: String) {
        check(openChapterIndex == null) { "chapter $openChapterIndex was not finished before $chapterIndex" }
        openChapterIndex = chapterIndex
        openChapterTitle = title
        pendingIntroNovel = novelTitle
        pendingIntroChapter = chapterTitle
        pendingIntroText = buildChapterIntro(novelTitle, chapterTitle)
        pendingParagraphs = mutableListOf()
        pendingIntroSpan = null
    }

    /** Закрывает intro главы, записывая его длительность в таймлайн. */
    fun endIntro(actualDurationMs: Long) {
        check(pendingIntroText != null) { "beginChapter was not called" }
        pendingIntroText = null
        pendingIntroSpan = advance(actualDurationMs)
    }

    /** Добавляет абзац текущей главы с его фактической длительностью. */
    fun addParagraph(paragraphIndex: Int, text: String, actualDurationMs: Long) {
        check(pendingIntroSpan != null) { "intro must be finished before paragraphs" }
        pendingParagraphs += AudiobookParagraphTiming(
            paragraphIndex = paragraphIndex,
            span = advance(actualDurationMs),
            text = text,
        )
    }

    /**
     * Закрывает главу и возвращает её запись. Следующая глава начнётся
     * ровно с [AudiobookChapterTiming.span.endMs].
     */
    fun endChapter(): AudiobookChapterTiming {
        val index = openChapterIndex ?: error("no chapter is open")
        val introSpan = pendingIntroSpan ?: error("chapter $index has no intro span")
        val timing = AudiobookChapterTiming(
            chapterIndex = index,
            title = openChapterTitle,
            span = AudiobookSpan(
                startMs = introSpan.startMs,
                endMs = currentMs,
                durationMs = currentMs - introSpan.startMs,
            ),
            intro = AudiobookIntroTiming(
                span = introSpan,
                novelTitle = pendingIntroNovel,
                chapterTitle = pendingIntroChapter,
            ),
            paragraphs = pendingParagraphs.toList(),
        )
        chapters += timing
        openChapterIndex = null
        openChapterTitle = ""
        pendingParagraphs = mutableListOf()
        pendingIntroSpan = null
        return timing
    }

    /** Все закрытые главы в порядке генерации. */
    fun build(): List<AudiobookChapterTiming> = chapters.toList()

    private fun advance(durationMs: Long): AudiobookSpan {
        val start = currentMs
        currentMs += durationMs
        return AudiobookSpan(startMs = start, endMs = currentMs, durationMs = durationMs)
    }
}

/** Ошибка валидации таймлайна перед записью JSON. */
class TimelineValidationException(message: String) : IllegalStateException(message)

/**
 * Проверка таймлайна перед сериализацией.
 *
 * Гарантирует монотонность глав и абзацев, непротиворечивость врезок
 * и совпадение конца последней главы с итоговой длительностью.
 */
fun validateTimeline(
    chapters: List<AudiobookChapterTiming>,
    expectedTotalMs: Long,
    toleranceMs: Long = 60L,
) {
    if (chapters.isEmpty()) throw TimelineValidationException("timeline has no chapters")
    if (expectedTotalMs <= 0L) throw TimelineValidationException("total duration is not positive: $expectedTotalMs")

    var previousChapterEnd = 0L
    chapters.forEachIndexed { index, chapter ->
        requireSpanPositive("chapter ${chapter.chapterIndex}", chapter.span)
        requireSpanConsistent("chapter ${chapter.chapterIndex}", chapter.span)
        if (chapter.span.startMs != previousChapterEnd) {
            throw TimelineValidationException(
                "chapter ${chapter.chapterIndex} starts at ${chapter.span.startMs}, expected $previousChapterEnd",
            )
        }
        if (chapter.span.startMs < 0L) {
            throw TimelineValidationException("chapter ${chapter.chapterIndex} has negative start")
        }
        if (chapter.intro.span.startMs != chapter.span.startMs) {
            throw TimelineValidationException("chapter ${chapter.chapterIndex} intro does not start with the chapter")
        }
        if (chapter.intro.span.endMs > chapter.span.endMs) {
            throw TimelineValidationException("chapter ${chapter.chapterIndex} intro ends after the chapter")
        }

        var previousParagraphEnd = chapter.intro.span.endMs
        var previousParagraphIndex = -1
        chapter.paragraphs.forEachIndexed { pIndex, paragraph ->
            val label = "chapter ${chapter.chapterIndex} paragraph $pIndex"
            requireSpanPositive(label, paragraph.span)
            requireSpanConsistent(label, paragraph.span)
            if (paragraph.span.startMs != previousParagraphEnd) {
                throw TimelineValidationException(
                    "$label starts at ${paragraph.span.startMs}, expected $previousParagraphEnd",
                )
            }
            if (paragraph.span.startMs < chapter.span.startMs) {
                throw TimelineValidationException("$label starts before its chapter")
            }
            if (paragraph.span.endMs > chapter.span.endMs) {
                throw TimelineValidationException("$label ends after its chapter")
            }
            // Индексы идут по исходному списку абзацев и могут иметь пропуски:
            // абзац, ставший пустым после очистки, не озвучивается. Важна
            // только строгая монотонность, а не непрерывность.
            if (paragraph.paragraphIndex <= previousParagraphIndex) {
                throw TimelineValidationException(
                    "$label has non-increasing index ${paragraph.paragraphIndex} " +
                        "after $previousParagraphIndex",
                )
            }
            previousParagraphIndex = paragraph.paragraphIndex
            previousParagraphEnd = paragraph.span.endMs
        }
        if (previousParagraphEnd != chapter.span.endMs) {
            throw TimelineValidationException(
                "chapter ${chapter.chapterIndex} ends at ${chapter.span.endMs} but its last span ends at $previousParagraphEnd",
            )
        }
        previousChapterEnd = chapter.span.endMs
    }

    val lastEnd = chapters.last().span.endMs
    if (kotlin.math.abs(lastEnd - expectedTotalMs) > toleranceMs) {
        throw TimelineValidationException(
            "last chapter ends at $lastEnd but total duration is $expectedTotalMs (tolerance ${toleranceMs}ms)",
        )
    }
}

private fun requireSpanPositive(label: String, span: AudiobookSpan) {
    if (span.durationMs <= 0L) {
        throw TimelineValidationException("$label has non-positive duration ${span.durationMs}")
    }
}

private fun requireSpanConsistent(label: String, span: AudiobookSpan) {
    if (span.endMs - span.startMs != span.durationMs) {
        throw TimelineValidationException(
            "$label is inconsistent: ${span.endMs} - ${span.startMs} != ${span.durationMs}",
        )
    }
}
