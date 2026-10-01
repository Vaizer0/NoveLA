package my.noveldokusha.tooling.audiobook

/** Ошибка валидации выбранного диапазона глав. */
class InvalidChapterRangeException(message: String) : IllegalArgumentException(message)

/** Валидированный диапазон глав в позициях (0-based, как `Chapter.position`). */
data class ChapterRange(
    val startPosition: Int,
    val endPosition: Int,
) {
    val count: Int get() = endPosition - startPosition + 1

    /** Границы для JSON: позиции 1-based, как их показывает пользователю. */
    val displayStart: Int get() = startPosition + 1
    val displayEnd: Int get() = endPosition + 1
}

/**
 * Валидация диапазона глав.
 *
 * Проверяет ровно то, что обещает UI: начало не раньше первой доступной
 * главы, конец не позже последней, начало не больше конца.
 */
fun validateChapterRange(
    startPosition: Int,
    endPosition: Int,
    firstAvailablePosition: Int,
    lastAvailablePosition: Int,
): ChapterRange {
    if (firstAvailablePosition > lastAvailablePosition) {
        throw InvalidChapterRangeException("the book has no chapters available for export")
    }
    if (startPosition < firstAvailablePosition) {
        throw InvalidChapterRangeException(
            "start chapter $startPosition is before the first available chapter $firstAvailablePosition",
        )
    }
    if (endPosition > lastAvailablePosition) {
        throw InvalidChapterRangeException(
            "end chapter $endPosition is after the last available chapter $lastAvailablePosition",
        )
    }
    if (startPosition > endPosition) {
        throw InvalidChapterRangeException(
            "start chapter $startPosition is after end chapter $endPosition",
        )
    }
    return ChapterRange(startPosition = startPosition, endPosition = endPosition)
}

/**
 * Позиции скачанных глав, доступных для экспорта.
 *
 * Порядок — по `position`, как в ридере; дубликаты схлопываются.
 */
fun availableExportPositions(
    allPositions: List<Int>,
    downloadedPositions: Set<Int>,
): List<Int> = allPositions
    .asSequence()
    .filter { it in downloadedPositions }
    .distinct()
    .sorted()
    .toList()
