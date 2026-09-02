package my.noveldokusha.video_export

import android.graphics.RectF
import android.text.Layout

/**
 * Разрешённая геометрия конвейера абзацев для конкретного [VideoStyleSnapshot].
 * Чистая функция стиля → прямоугольники канваса 1920×1080: рендер больше НЕ
 * читает хардкод-константы [VideoLayoutSpec] напрямую.
 *
 * При дефолтном стиле все числа равны старым константам [VideoLayoutSpec],
 * поэтому существующие кадры/QA-инварианты не меняются.
 *
 * [marginX] сужает колонку с двух сторон; [VideoStyleSnapshot.maxTextWidth]
 * дополнительно ограничивает (и центрирует) текстовую область; [contentOffsetY]
 * сдвигает конвейер вниз (шапка остаётся закреплённой).
 */
class VideoLayoutConfig private constructor(
    private val style: VideoStyleSnapshot,
) {

    /** Геометрия центрированной авторазмерной карточки текущего абзаца. */
    data class CardGeometry(
        val card: RectF,
        val textWindow: RectF,
        val scale: Float,
    )

    val width: Int = VideoLayoutSpec.WIDTH
    val height: Int = VideoLayoutSpec.HEIGHT
    val contentCenterX: Float = width / 2f

    val marginX: Float = style.marginX
    val offsetY: Float = style.contentOffsetY

    val cardPadH: Float = style.cardPaddingH
    val cardPadTop: Float = style.cardPaddingTop
    val cardPadBottom: Float = style.cardPaddingBottom
    val cardCornerRadius: Float = style.cardCornerRadius
    val cardStrokeWidth: Float = style.cardStrokeWidth

    val previewScale: Float = VideoLayoutSpec.PREVIEW_SCALE
    val transitionUs: Long = VideoLayoutSpec.TRANSITION_US
    val fontMinAutofit: Float = VideoLayoutSpec.FONT_MIN_AUTOFIT

    /** Непрозрачность контекстных абзацев из стиля (дефолт — старый PREVIEW_ALPHA). */
    val previewAlpha: Float = style.contextParagraphOpacity

    /** Дополнительный вертикальный зазор между current-карточкой и контекстом. */
    val paragraphSpacing: Float = style.paragraphSpacing

    // ── Типографика (прокидывается в ParagraphLayoutCache) ────────────────
    val fontSizePx: Float = style.fontSizePx
    val letterSpacing: Float = style.letterSpacing
    val lineHeight: Float = style.lineHeight
    val bold: Boolean = style.bold
    val italic: Boolean = style.italic
    val fontFamily: String = style.fontFamily
    val textAlignment: Layout.Alignment = when (style.textAlignment) {
        TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
        TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
        TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
    }
    val highlightColorArgb: Int = style.highlightColorArgb
    val highlightAlpha: Float = style.highlightAlpha
    val highlightRadius: Float = style.highlightRadius
    val highlightPadding: Float = style.highlightPadding

    // ── Колонка и текстовая область ────────────────────────────────────────

    val leftArtwork: VideoArtwork? = style.leftArtwork
    val rightArtwork: VideoArtwork? = style.rightArtwork

    /** Занятая артом ширина с левого края (не более половины канваса). */
    fun leftArtworkX(): Float {
        val a = leftArtwork ?: return 0f
        return minOf(a.widthFraction * width, width * 0.5f)
    }

    /** Занятая артом ширина с правого края (не более половины канваса). */
    fun rightArtworkX(): Float {
        val a = rightArtwork ?: return 0f
        return minOf(a.widthFraction * width, width * 0.5f)
    }

    fun leftArtworkRect(): RectF? {
        val a = leftArtwork ?: return null
        val w = leftArtworkX()
        return RectF(0f, 0f, w, height.toFloat())
    }

    fun rightArtworkRect(): RectF? {
        val a = rightArtwork ?: return null
        val w = rightArtworkX()
        return RectF(width - w, 0f, width.toFloat(), height.toFloat())
    }

    /** Безопасная от арта левая граница текста (максимум из арта и margin). */
    fun safeTextLeft(): Float = maxOf(marginX, leftArtworkX() + VideoArtwork.GAP_PX)

    /** Безопасная от арта правая граница текста. */
    fun safeTextRight(): Float = minOf(
        width - marginX,
        width.toFloat() - rightArtworkX() - VideoArtwork.GAP_PX,
    )

    fun columnLeft(): Float = safeTextLeft()

    fun columnRight(): Float = safeTextRight()

    fun columnWidth(): Float = (columnRight() - columnLeft()).coerceAtLeast(0f)

    /** Ширина текстовой области с учётом паддинга карточки и maxTextWidth. */
    fun cardTextWidth(): Float {
        val byPadding = columnWidth() - cardPadH * 2f
        val max = style.maxTextWidth
        if (max == null || max <= 0f) return byPadding.coerceAtLeast(1f)
        return minOf(byPadding, max).coerceAtLeast(1f)
    }

    /** X левого края текстовой области гипотезы (центрируется в колонке). */
    fun textX0(): Float {
        val padded = columnWidth() - cardPadH * 2f
        val x0 = columnLeft() + cardPadH + (padded - cardTextWidth()) / 2f
        val left = columnLeft()
        val right = columnRight()
        // Сортированные границы: деградантная (инвертированная) колонна не должна
        // выкидывать X за пределы отрезка [min, max].
        val lo = minOf(left, right)
        val hi = maxOf(left, right)
        return when {
            x0 < lo -> lo
            x0 > hi -> hi
            else -> x0
        }
    }

    fun cardTextMaxHeight(): Float =
        (cardCapBottom() - cardTop() - cardPadTop - cardPadBottom).coerceAtLeast(1f)

    // ── Зоны конвейера (канвас-координаты) ────────────────────────────────

    fun headerTop(): Float = VideoLayoutSpec.HEADER_TOP + offsetY
    fun headerBottom(): Float = VideoLayoutSpec.HEADER_BOTTOM + offsetY
    fun headerFontPx(): Float = VideoLayoutSpec.HEADER_FONT_PX
    fun headerAlpha(): Float = VideoLayoutSpec.HEADER_ALPHA

    fun prevTop(): Float = VideoLayoutSpec.PREV_TOP + offsetY
    fun prevBottom(): Float = VideoLayoutSpec.PREV_BOTTOM + offsetY - paragraphSpacing
    fun cardTop(): Float = VideoLayoutSpec.CARD_TOP + offsetY
    fun cardCapBottom(): Float = VideoLayoutSpec.CARD_CAP_BOTTOM + offsetY
    fun nextTop(): Float = VideoLayoutSpec.NEXT_TOP + offsetY + paragraphSpacing
    fun nextBottom(): Float = VideoLayoutSpec.NEXT_BOTTOM + offsetY + paragraphSpacing

    fun prevSlotRect(): RectF = RectF(columnLeft(), prevTop(), columnRight(), prevBottom())

    fun cardRect(): RectF = RectF(columnLeft(), cardTop(), columnRight(), cardCapBottom())

    fun nextSlotRect(): RectF = RectF(columnLeft(), nextTop(), columnRight(), nextBottom())

    fun cardContentRect(): RectF = RectF(
        textX0(), cardTop() + cardPadTop,
        textX0() + cardTextWidth(), cardCapBottom() - cardPadBottom,
    )

    // ── Авто-размерная карточка текущего абзаца (hugs в live-TTS стиле) ─────
    // Вместо фиксированной полосы 290..810 текущий абзац получает карточку,
    // высота которой равна высоте его текста + отступы (hug), а весь кластер
    // (prev-полоса + карточка + next-полоса) центрируется по вертикали в сво-
    // бодной области ниже шапки. Длинные абзацы вписываются через autofit, а
    // если и после пола не помещаются — прокручиваются (scroll), но контекст
    // (prev/next) остаётся.

    /** Высота контекстной полосы prev/next (превью). */
    val previewBandHeight: Float get() = 120f

    /** Зазор между карточкой и контекстными полосами. */
    val contextGap: Float get() = VideoLayoutSpec.SLOT_GAP.toFloat()

    /** Верх свободной вертикальной области (сразу под шапкой). */
    fun contentTop(): Float = headerBottom() + 24f

    /** Низ свободной вертикальной области. */
    fun contentBottom(): Float = height.toFloat() - 40f

    /** Максимальная высота текстовой области карточки (оставляет место контексту). */
    fun maxCardContentHeight(): Float =
        (contentBottom() - contentTop() - (previewBandHeight + contextGap) * 2f)
            .coerceAtLeast(120f)

    /**
     * Масштаб текста текущего абзаца: 1.0 (hug) пока текст помещается,
     * иначе autofit вписывает, но не больше пола, оставляя контекст.
     */
    fun contentScale(layoutHeightPx: Float): Float = when {
        layoutHeightPx <= maxCardContentHeight() -> 1f
        else -> maxOf(fontMinAutofit, maxCardContentHeight() / layoutHeightPx)
    }

    /** Геометрия центрированной авторазмерной карточки для текущего абзаца. */
    fun currentCardGeometry(layoutHeightPx: Float): CardGeometry {
        val scale = contentScale(layoutHeightPx)
        val contentH = layoutHeightPx * scale
        val cardH = contentH + cardPadTop + cardPadBottom
        val clusterH = previewBandHeight + contextGap + cardH + contextGap + previewBandHeight
        val freeSpace = contentBottom() - contentTop()
        val clusterTop = (contentTop() + (freeSpace - clusterH) / 2f).coerceAtLeast(contentTop())
        val cardTop = clusterTop + previewBandHeight + contextGap
        val cardBottom = cardTop + cardH
        val l = columnLeft()
        val r = columnRight()
        val card = RectF(l, cardTop, r, cardBottom)
        val window = RectF(
            textX0(), cardTop + cardPadTop,
            textX0() + cardTextWidth(), cardBottom - cardPadBottom,
        )
        return CardGeometry(card = card, textWindow = window, scale = scale)
    }

    /** prev контекстная полоса для карточки [card]. */
    fun prevBandFor(card: CardGeometry): RectF =
        RectF(columnLeft(), card.card.top - contextGap - previewBandHeight, columnRight(), card.card.top - contextGap)

    /** next контекстная полоса для карточки [card]. */
    fun nextBandFor(card: CardGeometry): RectF =
        RectF(columnLeft(), card.card.bottom + contextGap, columnRight(), card.card.bottom + contextGap + previewBandHeight)

    /** Полная карточка (фон) по текстовому окну [window]. */
    fun cardRectFor(window: RectF): RectF = RectF(
        columnLeft(),
        window.top - cardPadTop,
        columnRight(),
        window.bottom + cardPadBottom,
    )

    /** Прямоугольный бэнд клиппинга между двумя слотами. */
    fun band(top: RectF, bottom: RectF): RectF = RectF(
        columnLeft(),
        minOf(top.top, bottom.top),
        columnRight(),
        maxOf(top.bottom, bottom.bottom),
    )

    // ── Conveyor corridor windows (strict clips) ──────────────────────────
    // Each conveyor "layer" owns a pairwise-disjoint window. A paragraph is
    // drawn ONLY inside its own window: the transition can never push glyphs
    // into a neighbour's window, the card, or another slot.

    /** prev corridor (above the card, up to the card's top edge). */
    fun prevWindow(): RectF = RectF(columnLeft(), prevTop(), columnRight(), cardTop())

    /** current window = the card's inner text area. */
    fun cardWindow(): RectF = cardContentRect()

    /** next corridor (below the card). */
    fun nextWindow(): RectF = RectF(columnLeft(), nextTop(), columnRight(), nextBottom())

    /** Top of the card's text area (padding accounted for). */
    fun cardContentTop(): Float = cardTop() + cardPadTop

    /** Bottom of the card's text area. */
    fun cardContentBottom(): Float = cardCapBottom() - cardPadBottom

    /** Launch top for the incoming current paragraph (below the next window). */
    fun nextPreRollTop(): Float = nextTop() + (nextBottom() - nextTop())

    /** Auto-fit scale: fit into the card, but never smaller than [fontMinAutofit]. */
    fun autofitScale(layoutHeightPx: Float): Float = when {
        layoutHeightPx <= cardTextMaxHeight() -> 1f
        else -> maxOf(fontMinAutofit, cardTextMaxHeight() / layoutHeightPx)
    }

    /** Horizontal bounds of slot content in canvas coordinates. */
    fun slotContentLeftPx(scale: Float): Float =
        textX0() + cardTextWidth() * (1f - scale) / 2f

    fun slotContentRightPx(scale: Float): Float =
        slotContentLeftPx(scale) + cardTextWidth() * scale

    companion object {
        fun from(style: VideoStyleSnapshot): VideoLayoutConfig = VideoLayoutConfig(style)
    }
}