package my.noveldokusha.reader_visuals

import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout

/**
 * Семантика подсветки слов, перенесённая из RoundedBackgroundSpan читалки
 * в canvas-хелпер для видеорендера (и тестов).
 *
 * Читалка рисует скруглённый прямоугольник вокруг текста сдвигом по
 * ascent/descent строки и альфой 0x80 поверх цвета. Видео повторяет это
 * построчно через StaticLayout: для каждого слова получаем набор RectF
 * (по одному на затронутую строку), которые затем рисуются до текста.
 */
object HighlightSpan {

    /** Цвет подсветки с принудительной альфой 0x80 (как в читалке). */
    fun highlightColor(colorArgb: Int): Int =
        (colorArgb and 0x00FFFFFF) or (0x80 shl 24)

    /** Paint для заливки подсветки (не рисует текст). */
    fun paint(colorArgb: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = highlightColor(colorArgb)
        style = Paint.Style.FILL
    }

    /**
     * Прямоугольники подсветки для диапазона [start, end) внутри [layout] —
     * по одному на каждую пересечённую строку.
     *
     * @param padY вертикальная подушка вокруг строки (в px), как glyph-pad у спэна.
     * @param start индекс первого подсвечиваемого char (в координатах текста layout).
     * @param end индекс, следующий за последним подсвечиваемым char (exclusive).
     */
    fun wordRects(
        layout: StaticLayout,
        start: Int,
        end: Int,
        padY: Float,
    ): List<RectF> {
        val rects = mutableListOf<RectF>()
        val safeStart = start.coerceIn(0, layout.text.length)
        val safeEnd = end.coerceIn(0, layout.text.length)
        if (safeStart >= safeEnd) return rects

        val lineCount = layout.lineCount
        for (line in 0 until lineCount) {
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line)
            if (lineEnd <= safeStart || lineStart >= safeEnd) continue

            val segStart = maxOf(safeStart, lineStart)
            val segEnd = minOf(safeEnd, lineEnd)
            if (segStart >= segEnd) continue

            val x0 = layout.getLineLeft(line) + layout.getPrimaryHorizontal(segStart)
            val x1 = layout.getLineLeft(line) + layout.getPrimaryHorizontal(segEnd)
            if (x1 <= x0) continue

            val top = layout.getLineTop(line) - padY
            val bottom = layout.getLineBottom(line) + padY
            rects.add(RectF(x0, top, x1, bottom))
        }
        return rects
    }
}