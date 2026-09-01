package my.noveldokusha.video_export

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import my.noveldokusha.reader_visuals.ReaderVisualSnapshot

/**
 * Кэш предварительно размеченных [StaticLayout] для абзацев видео.
 * Каждой абзац размечивается ровно один раз (полный layout + превью) —
 * на кадр цена рендера только draw + transform, без повторной разметки.
 *
 * Типографика повторяет читалку: textSize из [ReaderVisualSnapshot.derivedBaseFontPx],
 * lineHeight — мультипликатор (setLineSpacing(0f, lineHeight)), как в ReaderItemAdapter,
 * letterSpacing — в em, как у TextView.
 */
class ParagraphLayoutCache(
    private val snapshot: ReaderVisualSnapshot,
    private val typeface: Typeface,
    private val textColorArgb: Int,
) {

    class ParagraphLayout(
        val paragraphIndex: Int,
        val text: String,
        val layout: StaticLayout,
        /** Масштаб автоподбора для карточки (1f, если текст помещается). */
        val autofitScale: Float,
    )

    private val entries = mutableMapOf<Int, ParagraphLayout>()

    private val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = snapshot.derivedBaseFontPx
        color = textColorArgb
        letterSpacing = snapshot.letterSpacing
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
            .obtain(displayText, 0, displayText.length, paint, VideoLayoutSpec.CARD_TEXT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, snapshot.lineHeight)
            .build()
        return ParagraphLayout(
            paragraphIndex = paragraphIndex,
            text = displayText,
            layout = layout,
            autofitScale = VideoLayoutSpec.autofitScale(layout.height.toFloat()),
        )
    }
}