package my.noveldokusha.video_export

/**
 * Детерминированное планирование показа слайдов по ОБЩЕЙ длительности аудио.
 *
 * Модель: упорядоченная последовательность "значков" (по одному на активный
 * слайд), каждый занимает временной интервал [startMs..endMs]. На произвольной
 * абсолютной позиции [timeMs] активен значок, чей интервал её содержит; режим
 * перехода (если активен) дополнительно даёт прогресс перехода 0..1 для
 * анимации. Финал: первый значок стартует с 0, последний значок задерживается
 * до конца общей длительности (никаких пустых хвостов).
 *
 * Все режимы — чистая функция (totalAudioMs, config, items) — никакого
 * состояния, часов или устройства.
 */
class SlideshowScheduler(
    private val config: SlideshowConfig,
    /** Упорядоченные активные слайды (уже отфильтровано по enabled и файлу). */
    items: List<ArtworkItem>,
    /** Общая длительность аудио, мс (> 0). */
    totalAudioMs: Long,
) {
    /** Значок показа: индекс слайда + временной интервал cмены. */
    data class SlideSlot(val itemIndex: Int, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    /** Состояние кадра: активный слайд + (опц.) параметры перехода. */
    data class Frame(
        val itemIndex: Int,
        /** Идентификатор устойчивого меню слайда (для декодера/кэша). */
        val fileName: String,
        /** Начало перехода в это значение, мс; -1 — переход не активен. */
        val transitionStartMs: Long,
        /** Конец перехода в это значение, мс; -1 — не активен. */
        val transitionEndMs: Long,
        /** Прогресс перехода 0..1 (0 — новая картинка не видна, 1 — полностью). */
        val progress: Float,
        /** Индекс слайда до перехода (картинка ухода при crossfade). */
        val fromIndex: Int = -1,
    ) {
        val transitioning: Boolean get() = transitionStartMs >= 0
    }

    private val slots: List<SlideSlot>
    private val items: List<ArtworkItem> = items
    private val totalMs: Long

    val isEmpty: Boolean get() = slots.isEmpty()

    init {
        totalMs = totalAudioMs
        if (config.enabled && totalAudioMs > 0) {
            slots = buildSlots(config, items, totalAudioMs)
        } else {
            slots = emptyList()
        }
    }

    fun frameAt(timeMs: Long): Frame {
        val t = timeMs.coerceIn(0L, totalMs)
        val slots = slots
        if (slots.isEmpty()) {
            return Frame(-1, "", -1, -1, 1f)
        }
        var idx = 0
        while (idx < slots.lastIndex && slots[idx].endMs <= t) idx++
        val slot = slots[idx]
        val item = items[slot.itemIndex]

        var transStart = -1L
        var transEnd = -1L
        var progress = 1f
        var fromIdx = slot.itemIndex

        if (config.transitionType != SlideshowTransition.NONE) {
            if (idx > 0) {
                // переход входит в текущее значение из предыдущего
                transStart = slot.startMs
                transEnd = slot.startMs + config.transitionDurationMs
                if (t < transEnd) {
                    progress = ((t - transStart).toFloat() / (transEnd - transStart).toFloat())
                        .coerceIn(0f, 1f)
                    fromIdx = slots[idx - 1].itemIndex
                }
            } else {
                // первый слайд — просто виден (через белый фон), прогресс 1
                progress = 1f
                transStart = -1
                transEnd = -1
            }
        }

        return Frame(
            itemIndex = slot.itemIndex,
            fileName = item.fileName,
            transitionStartMs = transStart,
            transitionEndMs = transEnd,
            progress = progress,
            fromIndex = fromIdx.takeIf { it != slot.itemIndex } ?: -1,
        )
    }

    companion object {
        /**
         * Строит временную шкалу смен. Гарантии:
         *  - первый значок начинается с 0;
         *  - последнее значение держится до конца (required fill);
         *  - интервалы положительные (не нулевые/не отрицательные);
         *  - режим RANDOM: детерминированный по [config.randomSeed], без
         *    "патологически быстрых" смен (минимум = randomMinMs), сумма
         *    интервалов ≤ totalAudioMs, оставшееся время отдано последнему.
         */
        internal fun buildSlots(
            config: SlideshowConfig,
            items: List<ArtworkItem>,
            totalAudioMs: Long,
        ): List<SlideSlot> {
            if (items.isEmpty()) return emptyList()
            val out = ArrayList<SlideSlot>(items.size)
            when (config.timingMode) {
                SlideshowTimingMode.FIXED_INTERVAL -> {
                    val step = config.fixedIntervalMs.coerceAtLeast(1L)
                    var cursor = 0L
                    for ((k, _) in items.withIndex()) {
                        val end = if (k == items.lastIndex) totalAudioMs
                        else minOf(totalAudioMs, cursor + step)
                        out += SlideSlot(k, cursor.coerceAtMost(totalAudioMs), end)
                        cursor = end
                    }
                }
                SlideshowTimingMode.PERCENT_OF_TOTAL_DURATION -> {
                    val share = (totalAudioMs * config.percentageSections.coerceIn(0.01f, 0.99f))
                        .toLong().coerceAtLeast(1L)
                    var cursor = 0L
                    for ((k, _) in items.withIndex()) {
                        val end = if (k == items.lastIndex) totalAudioMs
                        else minOf(totalAudioMs, cursor + share)
                        out += SlideSlot(k, cursor.coerceAtMost(totalAudioMs), end)
                        cursor = end
                    }
                }
                SlideshowTimingMode.RANDOM_INTERVAL -> {
                    val rnd = java.util.Random(config.randomSeed)
                    var cursor = 0L
                    val minI = config.randomMinMs.coerceAtLeast(1L)
                    val maxI = config.randomMaxMs.coerceAtLeast(minI)
                    val span = maxI - minI
                    var lastSlot = 0
                    for (k in 0 until items.lastIndex) {
                        if (cursor >= totalAudioMs) break
                        val d = if (span <= 0L) minI else minI + (rnd.nextLong().rem(span).let { Math.floorMod(it, span) })
                        val end = minOf(totalAudioMs, cursor + d)
                        out += SlideSlot(k, cursor, end)
                        lastSlot = k
                        cursor = end
                    }
                    // Оставшееся время забирает последний активный значок; если
                    // длительность кончилась раньше использования всех слайдов —
                    // держим последний успевший показаться до конца.
                    val holder = if (cursor < totalAudioMs) items.lastIndex else lastSlot
                    if (out.none { it.itemIndex == holder }) {
                        out += SlideSlot(holder, cursor.coerceAtMost(totalAudioMs), totalAudioMs)
                    } else {
                        val li = out.indexOfLast { it.itemIndex == holder }
                        out[li] = SlideSlot(holder, out[li].startMs, totalAudioMs)
                    }
                }
            }
            // Упорядочиваем по startMs и чистим нулевые/инверсные.
            return out.sortedBy { it.startMs }.mapNotNull { slot ->
                if (slot.endMs > slot.startMs) slot else null
            }
        }

        /**
         * Детерминированный seed: стабильный хэш из identity главы + sourceId +
         * полного JSON конфига слайдшоу + идентичности списка изображений.
         * Смена шрифта/карточки НЕ перетасовывает; смена конфига/слайдов — да.
         */
        fun stableHash(vararg components: String): Long {
            var h = 1125899906842597L
            for (c in components) {
                for (ch in c) {
                    h = 31L * h + ch.code.toLong()
                }
            }
            return h
        }
    }
}
