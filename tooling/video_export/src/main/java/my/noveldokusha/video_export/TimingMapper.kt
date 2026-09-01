package my.noveldokusha.video_export

/**
 * Чистая логика перевода «очищенный-текст диапазона» (в координатах куска и
 * абзаца) в «display-диапазон» ТОЧНО отображаемого текста и абсолютный семпл.
 *
 * Вынесена отдельно, чтобы её можно было покрыть JVM-тестами без Android.
 */
object TimingMapper {

    /**
     * @param map карта char-офсетов (map[i] = индекс cleaned[i] в display-тексте).
     * @param chunkCleanedStart индекс первого символа куска в очищенном тексте абзаца.
     * @param start локальный start из onRangeStart (относительно куска).
     * @param end   локальный end из onRangeStart (относительно куска).
     * @return display-диапазон [s, e) в координатах display-текста, либо null,
     *   если отображение невозможно (пустая карта и т.п.).
     */
    fun displayRange(
        map: IntArray,
        chunkCleanedStart: Int,
        start: Int,
        end: Int,
    ): IntRange? {
        if (map.isEmpty()) return null
        val cleanedStart = (chunkCleanedStart + start).coerceIn(0, map.lastIndex)
        val cleanedEnd = (chunkCleanedStart + end).coerceIn(0, map.lastIndex)
        if (cleanedStart > cleanedEnd) return null
        val ds = map[cleanedStart]
        val de = (map[cleanedEnd] + 1).coerceAtMost(map.size)
        if (ds >= de) return null
        return ds until de
    }

    /**
     * Абсолютный семпл слова: начало текущего куска в полном аудио плюс frame.
     * frame не может быть отрицательным.
     */
    fun absoluteSample(chunkStartSample: Long, frame: Int): Long =
        chunkStartSample + frame.coerceAtLeast(0)
}
