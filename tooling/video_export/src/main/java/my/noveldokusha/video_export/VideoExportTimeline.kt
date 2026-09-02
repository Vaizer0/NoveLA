package my.noveldokusha.video_export

/**
 * Модель данных видео-экспорта: тайминги слов и абзацев в координатах семплов
 * итогового WAV (совпадают с кодируемой в MP4 дорожкой — привязка точная).
 *
 * Вся анимация/подсветка — чистая функция позиции семпла, поэтому рендер одной
 * и той же главы всегда даёт одинаковый MP4.
 */
data class WordTiming(
    /** Диапазон в координатах ТОЧНО отображаемого [ParagraphTiming.displayText]. */
    val displayRange: IntRange,
    /** Абсолютная позиция (семпл) в полном аудио. */
    val samplePosition: Long,
    val isApproximate: Boolean = false,
)

data class ParagraphTiming(
    /** Текст, который реально рендерится в видео. */
    val displayText: String,
    /** Очищенный текст, отправленный в синтез (для отладки/трассировки). */
    val cleanedText: String,
    /** Первый семпл абзаца в полном аудио. */
    val startSample: Long,
    /** Семпл сразу после последнего слова абзаца (конец). */
    val endSample: Long,
    /** Тайминги слов, отсортированные по samplePosition. */
    val wordTimings: List<WordTiming>,
)

/**
 * Вступительная озвучка названия главы (то же, что озвучивает живая читалка
 * при переходе на главу). Пока произносится заголовок — видео показывает
 * крупный титул и подсвечивает слова в такт звуку; затем конвейер абзацев.
 */
data class TitleTiming(
    /** Текст титула, который рисуется и озвучивается (координаты слов). */
    val displayText: String,
    /** Первый семпл титула (обычно 0). */
    val startSample: Long,
    /** Семпл сразу после последнего слова титула (= начало первого абзаца). */
    val endSample: Long,
    /** Тайминги слов титула, отсортированные по samplePosition. */
    val wordTimings: List<WordTiming>,
) {
    /** Слово титула, звучащее в момент [sample]. */
    fun wordAtSample(sample: Long): WordTiming? {
        if (wordTimings.isEmpty()) return null
        var lo = 0
        var hi = wordTimings.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = wordTimings[mid]
            if (sample < w.samplePosition) {
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        val found = wordTimings.getOrNull(hi)
        if (found != null && sample >= found.samplePosition) return found
        return wordTimings.firstOrNull()
    }
}

data class VideoExportTimeline(
    val sampleRate: Int,
    val channelCount: Int,
    val totalSamples: Long,
    val paragraphs: List<ParagraphTiming>,
    /** Озвученное название главы в начале; null — вступление отключено. */
    val title: TitleTiming? = null,
) {

    /** Абзац, активный в момент [sample]. null до первого и после последнего. */
    fun paragraphAtSample(sample: Long): ParagraphTiming? {
        if (paragraphs.isEmpty()) return null
        if (sample < paragraphs.first().startSample) return paragraphs.first()
        if (sample >= paragraphs.last().endSample) return paragraphs.last()
        var lo = 0
        var hi = paragraphs.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = paragraphs[mid]
            if (sample < p.startSample) {
                hi = mid - 1
            } else if (sample >= p.endSample) {
                lo = mid + 1
            } else {
                return p
            }
        }
        return paragraphs.getOrNull(minOf(lo, paragraphs.lastIndex)) ?: paragraphs.last()
    }

    /** Слово абзаца [p], звучащее в момент [sample]. */
    fun wordAtSample(sample: Long, p: ParagraphTiming): WordTiming? {
        if (p.wordTimings.isEmpty()) return null
        var lo = 0
        var hi = p.wordTimings.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = p.wordTimings[mid]
            if (sample < w.samplePosition) {
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        val found = p.wordTimings.getOrNull(hi)
        if (found != null && sample >= found.samplePosition) return found
        return p.wordTimings.firstOrNull()
    }
}
