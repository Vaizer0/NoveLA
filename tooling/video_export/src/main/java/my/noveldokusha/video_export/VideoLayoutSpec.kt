package my.noveldokusha.video_export

import android.graphics.RectF

/**
 * Замороженная геометрия 1920×1080 видеорендера (премиум-макет, секция 6 плана):
 * три абзаца — prev (превью 2 строк) / current (карточка) / next, конвейерный
 * переход вверх на один слот за [TRANSITION_US].
 *
 * Все значения — пиксели канваса 1920×1080, числа взяты из плана один в один.
 * Класс чистый (только RectF-геометрия) — покрывается JVM-тестами.
 */
object VideoLayoutSpec {

    const val WIDTH = 1920
    const val HEIGHT = 1080

    const val MARGIN_X = 256
    const val COLUMN_WIDTH = WIDTH - MARGIN_X * 2

    const val HEADER_TOP = 28
    const val HEADER_BOTTOM = 96
    const val HEADER_ALPHA = 0.5f
    const val HEADER_FONT_PX = 32f

    const val SLOT_GAP = 20

    const val PREV_TOP = 150
    const val PREV_BOTTOM = 270
    const val PREV_HEIGHT = PREV_BOTTOM - PREV_TOP

    const val CARD_TOP = 290
    const val CARD_CAP_BOTTOM = 810

    const val NEXT_TOP = 830
    const val NEXT_BOTTOM = 950
    const val NEXT_HEIGHT = NEXT_BOTTOM - NEXT_TOP

    const val CARD_PAD_H = 56
    const val CARD_PAD_TOP = 40
    const val CARD_PAD_BOTTOM = 48
    const val CARD_TEXT_WIDTH = COLUMN_WIDTH - CARD_PAD_H * 2
    const val CARD_TEXT_MAX_HEIGHT =
        CARD_CAP_BOTTOM - CARD_TOP - CARD_PAD_TOP - CARD_PAD_BOTTOM

    const val CARD_CORNER_RADIUS = 20f
    const val CARD_STROKE_WIDTH = 2f

    /** Превью prev/next: 2 строки ≈ 0.70 от базового размера. */
    const val PREVIEW_SCALE = 0.70f
    const val PREVIEW_ALPHA = 0.45f

    /** Пол автомасштаба текста карточки (план: FLOOR 0.72). */
    const val FONT_MIN_AUTOFIT = 0.72f

    const val FPS = 30
    const val FRAME_INTERVAL_US = 1_000_000L / FPS

    /** Длительность конвейерного перехода между абзацами, мкс. */
    const val TRANSITION_US = 300_000L

    /** X центра колонки — точка опоры центрированного scale-преобразования. */
    const val CONTENT_CENTER_X = WIDTH / 2f

    // ── Прямоугольники слотов (фиксированные) ──────────────────────────────

    fun prevSlotRect(): RectF = RectF(
        MARGIN_X.toFloat(), PREV_TOP.toFloat(),
        (MARGIN_X + COLUMN_WIDTH).toFloat(), PREV_BOTTOM.toFloat(),
    )

    fun cardRect(): RectF = RectF(
        MARGIN_X.toFloat(), CARD_TOP.toFloat(),
        (MARGIN_X + COLUMN_WIDTH).toFloat(), CARD_CAP_BOTTOM.toFloat(),
    )

    fun nextSlotRect(): RectF = RectF(
        MARGIN_X.toFloat(), NEXT_TOP.toFloat(),
        (MARGIN_X + COLUMN_WIDTH).toFloat(), NEXT_BOTTOM.toFloat(),
    )

    /** Внутренняя (под текстовую область карточки) прямоугольная область. */
    fun cardContentRect(): RectF = RectF(
        (MARGIN_X + CARD_PAD_H).toFloat(), (CARD_TOP + CARD_PAD_TOP).toFloat(),
        (MARGIN_X + COLUMN_WIDTH - CARD_PAD_H).toFloat(),
        (CARD_CAP_BOTTOM - CARD_PAD_BOTTOM).toFloat(),
    )

    /** Прямоугольный бэнд клиппинга между двумя слотовыми прямоугольниками. */
    fun band(top: RectF, bottom: RectF): RectF = RectF(
        MARGIN_X.toFloat(),
        minOf(top.top, bottom.top),
        (MARGIN_X + COLUMN_WIDTH).toFloat(),
        maxOf(top.bottom, bottom.bottom),
    )

    /** Превышающий (за нижний край) «старт» для всплывающего next в transition. */
    fun nextPreRollRect(): RectF = RectF(
        MARGIN_X.toFloat(), (NEXT_TOP + NEXT_HEIGHT).toFloat(),
        (MARGIN_X + COLUMN_WIDTH).toFloat(), (NEXT_BOTTOM + NEXT_HEIGHT).toFloat(),
    )

    // ── Математика ─────────────────────────────────────────────────────────

    fun smoothstep(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerpRect(a: RectF, b: RectF, t: Float): RectF = RectF(
        lerp(a.left, b.left, t),
        lerp(a.top, b.top, t),
        lerp(a.right, b.right, t),
        lerp(a.bottom, b.bottom, t),
    )

    /**
     * Автоподбор масштаба текста карточки: если высота layout'а больше
     * доступной текстовой высоты — вписать, но не меньше [FONT_MIN_AUTOFIT].
     */
    fun autofitScale(layoutHeightPx: Float): Float = when {
        layoutHeightPx <= CARD_TEXT_MAX_HEIGHT -> 1f
        else -> maxOf(FONT_MIN_AUTOFIT, CARD_TEXT_MAX_HEIGHT / layoutHeightPx)
    }

    /** Горизонтальные границы содержимого слотов в координатах канваса. */
    fun slotContentLeftPx(scale: Float): Float =
        CONTENT_CENTER_X + (MARGIN_X + CARD_PAD_H - CONTENT_CENTER_X) * scale

    fun slotContentRightPx(scale: Float): Float =
        CONTENT_CENTER_X + (MARGIN_X + CARD_PAD_H + CARD_TEXT_WIDTH - CONTENT_CENTER_X) * scale
}