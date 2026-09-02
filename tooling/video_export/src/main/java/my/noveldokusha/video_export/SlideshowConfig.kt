package my.noveldokusha.video_export

import org.json.JSONObject

/** Режим планирования таймингов слайдшоу по общей длительности аудио. */
enum class SlideshowTimingMode {
    /** Одинаковый фиксированный интервал между сменами слайдов. */
    FIXED_INTERVAL,
    /** Доля общей длительности на каждый слайд (слайды делят таймлайн поровну). */
    PERCENT_OF_TOTAL_DURATION,
    /** Случайные (детерминированные) интервалы в диапазоне [randomMinMs..randomMaxMs]. */
    RANDOM_INTERVAL,
}

/** Тип перехода между слайдами (детерминированный от абсолютного аудио-времени). */
enum class SlideshowTransition {
    /** Мгновенная смена, без анимации. */
    NONE,
    /** Плавное перекрёстное появление (crossfade) за [transitionDurationMs]. */
    FADE,
    /** Мягкий горизонтальный сдвиг нового слайда. */
    SUBTLE_SLIDE,
    /** Лёгкое масштабирование нового слайда. */
    SUBTLE_ZOOM,
}

/**
 * Конфигурация слайдшоу поверх абзацев (Phase G). Тайминги строятся
 * ИЗМЕНЯЕМОЙ функцией от общей длительности аудио (totalAudioMs), а не
 * фиксируются по абзацам: один и тот же конфиг детерминированно даёт одну и
 * ту же последовательность смен для одной и той же главы.
 *
 * Случайный режим использует детерминированный seed (во внешнем планировщике —
 * [SlideshowScheduler], seed = stableHash(chapterIdentity + sourceId +
 * slideshowJson + itemIds)), поэтому один и тот же материал всегда даёт
 * одинаковый экспорт, а смена конфига/набора слайдов — перетасовку.
 */
data class SlideshowConfig(
    /** Слайдшоу включено (иначе "NONE" — без слайдов, как в Phase F). */
    val enabled: Boolean,
    val timingMode: SlideshowTimingMode,
    /** Фиксированный интервал между сменами, мс (для FIXED_INTERVAL). */
    val fixedIntervalMs: Long,
    /** Доля общей длительности на слайд, 0..1 (для PERCENT_OF_TOTAL_DURATION). */
    val percentageSections: Float,
    /** Нижняя граница случайного интервала, мс (для RANDOM_INTERVAL). */
    val randomMinMs: Long,
    /** Верхняя граница случайного интервала, мс (для RANDOM_INTERVAL). */
    val randomMaxMs: Long,
    /** Детерминированный seed для RANDOM_INTERVAL. */
    val randomSeed: Long,
    val transitionType: SlideshowTransition,
    /** Длительность перехода, мс (FADE/SLIDE/ZOOM). */
    val transitionDurationMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ENABLED, enabled)
        put(KEY_MODE, timingMode.name)
        put(KEY_FIXED_MS, fixedIntervalMs)
        put(KEY_PERCENT, percentageSections.toDouble())
        put(KEY_RANDOM_MIN, randomMinMs)
        put(KEY_RANDOM_MAX, randomMaxMs)
        put(KEY_SEED, randomSeed)
        put(KEY_TRANSITION, transitionType.name)
        put(KEY_TRANSITION_MS, transitionDurationMs)
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "timingMode"
        private const val KEY_FIXED_MS = "fixedIntervalMs"
        private const val KEY_PERCENT = "percentageSections"
        private const val KEY_RANDOM_MIN = "randomMinMs"
        private const val KEY_RANDOM_MAX = "randomMaxMs"
        private const val KEY_SEED = "randomSeed"
        private const val KEY_TRANSITION = "transitionType"
        private const val KEY_TRANSITION_MS = "transitionDurationMs"

        /** Отключённое слайдшоу (дефолт — идентично Phase F, слайдов нет). */
        fun disabled(): SlideshowConfig = SlideshowConfig(
            enabled = false,
            timingMode = SlideshowTimingMode.FIXED_INTERVAL,
            fixedIntervalMs = 8_000L,
            percentageSections = 0.5f,
            randomMinMs = 4_000L,
            randomMaxMs = 10_000L,
            randomSeed = 0L,
            transitionType = SlideshowTransition.FADE,
            transitionDurationMs = 700L,
        )

        fun fromJson(obj: JSONObject?): SlideshowConfig {
            if (obj == null) return disabled()
            fun optLong(key: String, def: Long): Long = obj.optLong(key, def)
            fun optFloat(key: String, def: Float): Float =
                obj.optDouble(key, def.toDouble()).toFloat()
            val mode = runCatching {
                SlideshowTimingMode.valueOf(obj.getString(KEY_MODE))
            }.getOrDefault(SlideshowTimingMode.FIXED_INTERVAL)
            val transition = runCatching {
                SlideshowTransition.valueOf(obj.getString(KEY_TRANSITION))
            }.getOrDefault(SlideshowTransition.FADE)
            return SlideshowConfig(
                enabled = obj.optBoolean(KEY_ENABLED, false),
                timingMode = mode,
                fixedIntervalMs = optLong(KEY_FIXED_MS, 8_000L).coerceAtLeast(1_000L),
                percentageSections = optFloat(KEY_PERCENT, 0.5f).coerceIn(0.05f, 1f),
                randomMinMs = optLong(KEY_RANDOM_MIN, 4_000L).coerceAtLeast(1_000L),
                randomMaxMs = optLong(KEY_RANDOM_MAX, 10_000L),
                randomSeed = optLong(KEY_SEED, 0L),
                transitionType = transition,
                transitionDurationMs = optLong(KEY_TRANSITION_MS, 700L).coerceAtLeast(0L),
            )
        }
    }
}
