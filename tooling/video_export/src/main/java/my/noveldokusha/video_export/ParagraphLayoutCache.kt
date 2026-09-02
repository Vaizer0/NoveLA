package my.noveldokusha.video_export

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Кэш предварительно размеченных [StaticLayout] для абзацев видео.
 * Каждой абзац размечивается ровно один раз (полный layout + превью) —
 * на кадр цена рендера только draw + transform, без повторной разметки.
 *
 * Типографика полностью управляется [VideoLayoutConfig] (из замороженного
 * [VideoStyleSnapshot]): textSize/fontWeight/italic/letterSpacing/lineHeight
 * и ширина текстовой области. При дефолтном стиле параметры совпадают со
 * старой читалко-центричной версией один в один.
 */
class ParagraphLayoutCache(
    private val typeface: Typeface,
    private val textColorArgb: Int,
    private val config: VideoLayoutConfig,
) {

    class ParagraphLayout(
        val paragraphIndex: Int,
        val text: String,
        val layout: StaticLayout,
        /** Масштаб автоподбора для карточки (1f, если текст помещается). */
        val autofitScale: Float,
    )

    private val entries = mutableMapOf<Int, ParagraphLayout>()

    /** Доступен рендеру для постановки альфа-слота перед рисованием. */
    internal val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = config.fontSizePx
        color = textColorArgb
        letterSpacing = config.letterSpacing
    }

    /** Важно: paint.alpha мутируется при рисовании слотов — возврат к 255. */
    fun resetAlpha() {
        paint.alpha = 0xFF
    }

    fun layoutFor(paragraphIndex: Int, displayText: String): ParagraphLayout {
        entries[paragraphIndex]?.let { return it }
        return build(paragraphIndex, displayText).also { entries[paragraphIndex] = it }
    }

    private fun build(paragraphIndex: Int, displayText: String): ParagraphLayout {
        val layout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, paint, config.cardTextWidth().toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, config.lineHeight)
            .build()
        return ParagraphLayout(
            paragraphIndex = paragraphIndex,
            text = displayText,
            layout = layout,
            autofitScale = config.autofitScale(layout.height.toFloat()),
        )
    }
}