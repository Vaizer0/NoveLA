package my.noveldokusha.tooling.audiobook

import kotlinx.serialization.Serializable

/** Тайминг одного фрагмента главы (intro или абзац). */
@Serializable
data class AudiobookSpan(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
)

/** Абзац в таймлайне. Только абзацный уровень — без пословных таймингов. */
@Serializable
data class AudiobookParagraphTiming(
    val paragraphIndex: Int,
    val span: AudiobookSpan,
    val text: String,
)

/** Вступительная реплика главы: произнесённые название книги и главы. */
@Serializable
data class AudiobookIntroTiming(
    val span: AudiobookSpan,
    val novelTitle: String,
    val chapterTitle: String,
)

/** Глава в таймлайне. */
@Serializable
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
 *
 * Если задан [onChapterClosed], закрытые главы не накапливаются в памяти:
 * они валидируются инкрементально и сразу отдаются наружу (см. `ChapterSpool`).
 * Это позволяет держать в куче не больше одной главы даже на 1000+ глав.
 */
class TimelineBuilder(
    private val onChapterClosed: ((AudiobookChapterTiming) -> Unit)? = null,
) {
    private val chapters = mutableListOf<AudiobookChapterTiming>()

    /** Сколько глав уже закрыто (для потокового режима). */
    var closedCount: Int = 0
        private set

    private var lastClosedEndMs: Long = 0L

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
    val chapterCount: Int get() = if (onChapterClosed != null) closedCount else chapters.size

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
        if (onChapterClosed != null) {
            // Потоковый режим: глава проверяется сразу, а не хранится до конца.
            validateChapter(timing, expectedStartMs = lastClosedEndMs)
            onChapterClosed(timing)
            lastClosedEndMs = timing.span.endMs
            closedCount++
        } else {
            chapters += timing
        }
        openChapterIndex = null
        openChapterTitle = ""
        pendingParagraphs = mutableListOf()
        pendingIntroSpan = null
        return timing
    }

    /**
     * Восстанавливает уже готовые главы из чекпоинта перед продолжением
     * синтеза. Каждая глава проходит ту же проверку, что и [endChapter],
     * поэтому рассинхрон с чекпоинтом падает сразу, а не портит JSON.
     *
     * В потоковом режиме восстановленные главы повторно отдаются в
     * [onChapterClosed] (их нужно снова записать в spool), но TTS для них
     * не вызывается: аудио уже лежит в durable-накопителе.
     */
    fun restore(completed: List<AudiobookChapterTiming>) {
        check(openChapterIndex == null) { "cannot restore while chapter $openChapterIndex is open" }
        if (completed.isEmpty()) return
        var expectedStart = lastClosedEndMs
        completed.forEach { chapter ->
            validateChapter(chapter, expectedStartMs = expectedStart)
            expectedStart = chapter.span.endMs
            if (onChapterClosed != null) {
                onChapterClosed(chapter)
                closedCount++
            } else {
                chapters += chapter
            }
        }
        lastClosedEndMs = expectedStart
        currentMs = expectedStart
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
    chapters.forEach { chapter ->
        validateChapter(chapter, expectedStartMs = previousChapterEnd)
        previousChapterEnd = chapter.span.endMs
    }
    validateTotalDuration(previousChapterEnd, expectedTotalMs, toleranceMs)
}

/**
 * Проверяет одну главу целиком: монотонность, непротиворечивость врезок
 * и абзацев. Используется и пакетной валидацией, и потоковым режимом
 * [TimelineBuilder], где главы не накапливаются.
 */
internal fun validateChapter(chapter: AudiobookChapterTiming, expectedStartMs: Long) {
    requireSpanPositive("chapter ${chapter.chapterIndex}", chapter.span)
    requireSpanConsistent("chapter ${chapter.chapterIndex}", chapter.span)
    if (chapter.span.startMs != expectedStartMs) {
        throw TimelineValidationException(
            "chapter ${chapter.chapterIndex} starts at ${chapter.span.startMs}, expected $expectedStartMs",
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
}

/** Проверяет, что конец последней главы совпадает с длительностью медиа. */
internal fun validateTotalDuration(
    lastEndMs: Long,
    expectedTotalMs: Long,
    toleranceMs: Long = 60L,
) {
    if (kotlin.math.abs(lastEndMs - expectedTotalMs) > toleranceMs) {
        throw TimelineValidationException(
            "last chapter ends at $lastEndMs but total duration is $expectedTotalMs (tolerance ${toleranceMs}ms)",
        )
    }
}

/**
 * Подтягивает конец таймлайна к фактической длительности носителя.
 *
 * Синтез и таймлайн считаются по поданным PCM-кадрам, а AAC-дорожка из-за
 * задержки кодера оказывается на несколько десятков миллисекунд длиннее.
 * Чтобы `audio.durationMs` и `endMs` последней главы совпадали с реальным
 * файлом, дельта добавляется к последнему озвученному фрагменту последней
 * главы (абзацу, а если их нет — к intro). Промежуточные метки не трогаются:
 * расхождение локализовано там, где оно физически возникло — в хвосте.
 */
fun alignTimelineToDuration(
    chapters: List<AudiobookChapterTiming>,
    totalMs: Long,
): List<AudiobookChapterTiming> {
    if (chapters.isEmpty()) return chapters
    val aligned = alignLastChapter(chapters.last(), totalMs)
    return if (aligned === chapters.last()) chapters else chapters.dropLast(1) + aligned
}

/**
 * Подтягивает последнюю (уже закрытую) главу к фактической длительности.
 *
 * Возвращает исходный объект, если сдвиг не требуется или невозможен
 * (например, отрицательная итоговая длительность): вызывающий код должен
 * проверить итог через [validateTotalDuration].
 */
internal fun alignLastChapter(
    last: AudiobookChapterTiming,
    totalMs: Long,
): AudiobookChapterTiming {
    val delta = totalMs - last.span.endMs
    if (delta == 0L) return last

    val newParagraphs: List<AudiobookParagraphTiming>
    val newIntro: AudiobookIntroTiming
    if (last.paragraphs.isNotEmpty()) {
        val updated = last.paragraphs.toMutableList()
        val tail = updated.last()
        val newDuration = tail.span.durationMs + delta
        if (newDuration <= 0L) return last
        updated[updated.lastIndex] = tail.copy(
            span = tail.span.copy(endMs = totalMs, durationMs = newDuration),
        )
        newParagraphs = updated
        newIntro = last.intro
    } else {
        val newDuration = last.intro.span.durationMs + delta
        if (newDuration <= 0L) return last
        newIntro = last.intro.copy(
            span = last.intro.span.copy(endMs = totalMs, durationMs = newDuration),
        )
        newParagraphs = last.paragraphs
    }

    return last.copy(
        span = last.span.copy(endMs = totalMs, durationMs = totalMs - last.span.startMs),
        intro = newIntro,
        paragraphs = newParagraphs,
    )
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
