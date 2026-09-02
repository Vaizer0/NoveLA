package my.noveldokusha.text_to_speech

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.text.Layout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.SpannableString
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.InputStream

/** Immutable chapter-facing artwork/background snapshot; no image bytes are persisted in preferences. */
data class TtsVideoVisualSnapshot(
    val textStyle: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
    val backgroundBitmap: Bitmap? = null,
    val artworkBitmaps: List<Bitmap> = emptyList(),
)

data class SafeTextRect(val rect: RectF, val artworkRects: List<RectF>)

data class RenderedLayoutInfo(val bounds: RectF, val textSizePx: Float, val scrollY: Float)

class TtsVideoCompositionRenderer(private val context: Context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val imageCache = HashMap<String, Bitmap?>()

    fun loadBitmap(uri: String): Bitmap? {
        if (uri.isBlank()) return null
        if (imageCache.containsKey(uri)) return imageCache[uri]
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri)).use { input: InputStream? ->
                input?.let(BitmapFactory::decodeStream)
            }
        }.getOrNull()
        imageCache[uri] = bitmap
        return bitmap
    }

    fun safeTextRect(settings: TtsVideoVisualSettings): SafeTextRect {
        val margin = settings.safeMarginPx
        val canvas = RectF(margin, margin, settings.width - margin, settings.height - margin)
        val art = mutableListOf<RectF>()
        if (settings.artworkMode != ArtworkMode.NONE && settings.artworkUris.isNotEmpty() && !settings.artworkOverlay) {
            val w = settings.artworkWidthPx.coerceIn(120f, settings.width * .35f)
            when (settings.artworkMode) {
                ArtworkMode.LEFT -> { art += RectF(margin, margin, margin + w, settings.height - margin); canvas.left += w + 28f }
                ArtworkMode.RIGHT -> { art += RectF(settings.width - margin - w, margin, settings.width - margin, settings.height - margin); canvas.right -= w + 28f }
                ArtworkMode.BOTH -> {
                    val each = min(w, (settings.width - margin * 2f - 90f) / 2f)
                    art += RectF(margin, margin, margin + each, settings.height - margin)
                    art += RectF(settings.width - margin - each, margin, settings.width - margin, settings.height - margin)
                    canvas.left += each + 28f; canvas.right -= each + 28f
                }
                ArtworkMode.NONE -> Unit
            }
        }
        val maxWidth = settings.width * settings.maxTextWidthFraction
        if (canvas.width() > maxWidth) {
            val extra = (canvas.width() - maxWidth) / 2f
            canvas.left += extra; canvas.right -= extra
        }
        return SafeTextRect(canvas, art)
    }

    fun render(canvas: Canvas, timeline: TtsVideoTimeline, settings: TtsVideoVisualSettings, snapshot: TtsVideoVisualSnapshot, timeUs: Long): RenderedLayoutInfo? {
        require(settings.width > 0 && settings.height > 0 && settings.fps > 0)
        canvas.save()
        try {
            drawBackground(canvas, settings, snapshot, timeline.durationUs, timeUs)
            drawArtwork(canvas, settings, snapshot, timeline.durationUs, timeUs)
            val current = timeline.paragraphAt(timeUs) ?: return null
            val safe = safeTextRect(settings)
            val visible = visibleParagraphs(timeline, current, settings, safe.rect.height())
            visible.forEach { paragraph ->
                val isCurrent = paragraph.id == current.id
                val alpha = if (isCurrent) 1f else .62f
                val scale = if (isCurrent) 1f else .78f
                drawParagraph(canvas, paragraph, current, settings, snapshot, safe.rect, timeUs, alpha, scale)
            }
            return layoutInfo(canvas, current, settings, safe.rect, timeUs)
        } finally {
            canvas.restore()
        }
    }

    private fun drawBackground(canvas: Canvas, settings: TtsVideoVisualSettings, snapshot: TtsVideoVisualSnapshot, durationUs: Long, timeUs: Long) {
        val bitmap = chooseSlideshowBitmap(snapshot.artworkBitmaps, settings, durationUs, timeUs)
        when (settings.backgroundMode) {
            BackgroundMode.SOLID, BackgroundMode.PRESET -> canvas.drawColor(settings.backgroundColor)
            BackgroundMode.IMAGE -> if (snapshot.backgroundBitmap != null) drawCover(canvas, snapshot.backgroundBitmap) else canvas.drawColor(settings.backgroundColor)
        }
        if (settings.slideshowEnabled && bitmap != null) {
            val alpha = slideshowAlpha(settings, durationUs, timeUs)
            paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
            drawCover(canvas, bitmap, paint)
            paint.alpha = 255
        }
    }

    private fun chooseSlideshowBitmap(bitmaps: List<Bitmap>, settings: TtsVideoVisualSettings, durationUs: Long, timeUs: Long): Bitmap? {
        if (!settings.slideshowEnabled || bitmaps.isEmpty() || durationUs <= 0) return null
        val stable = settings.slideshowSeed xor durationUs xor bitmaps.size.toLong()
        val slot = when (settings.slideshowIntervalMode) {
            SlideshowIntervalMode.FIXED_INTERVAL -> max(1L, settings.slideshowIntervalMs)
            SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION -> max(1L, durationUs / max(1, bitmaps.size))
            SlideshowIntervalMode.RANDOM_INTERVAL -> {
                var x = stable
                x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                max(1L, settings.slideshowIntervalMs / 2L + abs(x % max(1L, settings.slideshowIntervalMs)))
            }
        }
        val index = ((timeUs / slot) % bitmaps.size).toInt()
        return bitmaps[index]
    }

    private fun slideshowAlpha(settings: TtsVideoVisualSettings, durationUs: Long, timeUs: Long): Float {
        if (settings.slideshowTransition == SlideshowTransition.NONE || durationUs <= 0) return .30f
        val interval = when (settings.slideshowIntervalMode) {
            SlideshowIntervalMode.FIXED_INTERVAL -> max(1L, settings.slideshowIntervalMs)
            SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION -> max(1L, durationUs / max(1, 2))
            SlideshowIntervalMode.RANDOM_INTERVAL -> max(1L, settings.slideshowIntervalMs)
        }
        val phase = (timeUs % interval).toFloat() / interval.toFloat()
        return when (settings.slideshowTransition) {
            SlideshowTransition.FADE, SlideshowTransition.CROSSFADE -> .18f + .18f * (if (phase < .5f) phase * 2f else (1f - phase) * 2f)
            SlideshowTransition.SUBTLE_SLIDE, SlideshowTransition.SUBTLE_ZOOM, SlideshowTransition.NONE -> .20f
        }
    }

    private fun drawArtwork(canvas: Canvas, settings: TtsVideoVisualSettings, snapshot: TtsVideoVisualSnapshot, durationUs: Long, timeUs: Long) {
        if (snapshot.artworkBitmaps.isEmpty() || settings.artworkMode == ArtworkMode.NONE) return
        val safe = safeTextRect(settings)
        val selected = snapshot.artworkBitmaps.take(when (settings.artworkMode) { ArtworkMode.BOTH -> 2; else -> 1 })
        selected.forEachIndexed { index, bitmap ->
            val bounds = safe.artworkRects.getOrNull(index) ?: return@forEachIndexed
            canvas.save()
            try {
                val clip = Path().apply { addRoundRect(bounds, settings.artworkCornerRadiusPx, settings.artworkCornerRadiusPx, Path.Direction.CW) }
                canvas.clipPath(clip)
                paint.alpha = (settings.artworkOpacity * 255).roundToInt().coerceIn(0, 255)
                drawCover(canvas, bitmap, paint, bounds)
                paint.alpha = 255
                if (settings.artworkBorderWidthPx > 0f) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = settings.artworkBorderWidthPx; paint.color = settings.artworkBorderColor
                    canvas.drawRoundRect(bounds, settings.artworkCornerRadiusPx, settings.artworkCornerRadiusPx, paint)
                    paint.style = Paint.Style.FILL
                }
            } finally { canvas.restore() }
        }
    }

    private fun visibleParagraphs(timeline: TtsVideoTimeline, current: VideoParagraph, settings: TtsVideoVisualSettings, height: Float): List<VideoParagraph> {
        if (settings.paragraphMode == ParagraphDisplayMode.CURRENT_ONLY) return listOf(current)
        val result = mutableListOf(current)
        val currentIndex = current.blockIndex
        val maxContext = when (settings.paragraphMode) {
            ParagraphDisplayMode.CURRENT_WITH_CONTEXT -> 2
            ParagraphDisplayMode.DYNAMIC_CONTEXT -> Int.MAX_VALUE
            ParagraphDisplayMode.CURRENT_ONLY -> 0
        }
        var radius = 1
        while (result.size < maxContext + 1 && radius < 32) {
            val candidate = timeline.paragraphs.firstOrNull { it.blockIndex == currentIndex - radius }
                ?: timeline.paragraphs.firstOrNull { it.blockIndex == currentIndex + radius }
            if (candidate != null) result += candidate else break
            radius++
        }
        return result.distinctBy { it.id }.sortedBy { it.startUs }
    }

    private fun drawParagraph(canvas: Canvas, paragraph: VideoParagraph, current: VideoParagraph, settings: TtsVideoVisualSettings, snapshot: TtsVideoVisualSnapshot, rect: RectF, timeUs: Long, alpha: Float, scale: Float) {
        val baseSize = if (paragraph.id == current.id) fitTextSize(paragraph.displayText, settings, rect.width(), rect.height()) else settings.fontSizePx * .78f
        val layout = makeLayout(paragraph.displayText, baseSize, settings, rect.width().roundToInt(), snapshot.textStyle)
        val card = RectF(0f, 0f, layout.width.toFloat() + settings.cardPaddingPx * 2f, layout.height.toFloat() + settings.cardPaddingPx * 2f)
        val x = rect.centerX() - card.width() / 2f
        val y = rect.centerY() - card.height() / 2f + if (paragraph.id == current.id) scrollOffset(layout, paragraph, settings, timeUs) else 0f
        canvas.save()
        try {
            canvas.translate(rect.centerX(), rect.centerY())
            canvas.scale(scale, scale)
            canvas.translate(-card.width() / 2f, -card.height() / 2f)
            paint.alpha = (alpha * settings.cardAlpha * 255).roundToInt().coerceIn(0, 255)
            if (settings.cardEnabled) {
                paint.style = Paint.Style.FILL; paint.color = settings.cardColor
                canvas.drawRoundRect(0f, 0f, card.width(), card.height(), settings.cardCornerRadiusPx, settings.cardCornerRadiusPx, paint)
                if (settings.cardStrokeWidthPx > 0f) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = settings.cardStrokeWidthPx; paint.color = settings.cardStrokeColor
                    canvas.drawRoundRect(0f, 0f, card.width(), card.height(), settings.cardCornerRadiusPx, settings.cardCornerRadiusPx, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            canvas.translate(settings.cardPaddingPx, settings.cardPaddingPx)
            textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
            layout.draw(canvas)
            if (paragraph.id == current.id) drawHighlight(canvas, layout, paragraph, settings, timeUs)
        } finally { canvas.restore() }
    }

    private fun drawHighlight(canvas: Canvas, layout: StaticLayout, paragraph: VideoParagraph, settings: TtsVideoVisualSettings, timeUs: Long) {
        val range = paragraph.spokenRanges.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs } ?: return
        val start = range.displayStart.coerceIn(0, layout.text.length)
        val end = range.displayEnd.coerceIn(start, layout.text.length)
        path.reset(); layout.getSelectionPath(start, end, path)
        paint.color = settings.highlightColor; paint.alpha = (settings.highlightAlpha * 255).roundToInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
        // The selection path is the exact StaticLayout glyph geometry; redraw text so the highlight sits behind glyphs.
        textPaint.alpha = 255
        layout.draw(canvas)
        paint.alpha = 255
    }

    private fun fitTextSize(text: String, settings: TtsVideoVisualSettings, width: Float, height: Float): Float {
        var size = settings.fontSizePx
        val minSize = settings.minFontSizePx.coerceAtLeast(16f)
        while (size > minSize) {
            val layout = makeLayout(text, size, settings, width.roundToInt(), Typeface.SANS_SERIF)
            if (layout.height <= height * .78f && layout.width <= width) return size
            size -= 2f
        }
        return minSize
    }

    private fun makeLayout(text: String, size: Float, settings: TtsVideoVisualSettings, width: Int, typeface: Typeface): StaticLayout {
        textPaint.textSize = size
        textPaint.color = settings.textColor
        textPaint.typeface = typeface
        textPaint.isAntiAlias = true
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .setLineSpacing(settings.paragraphSpacingPx, settings.lineSpacingMultiplier)
        return builder.build()
    }

    private fun scrollOffset(layout: StaticLayout, paragraph: VideoParagraph, settings: TtsVideoVisualSettings, timeUs: Long): Float {
        if (settings.longParagraphMode != LongParagraphMode.SMOOTH_SCROLL || paragraph.spokenRanges.isEmpty()) return 0f
        val active = paragraph.spokenRanges.firstOrNull { timeUs in it.startUs until it.endUs } ?: return 0f
        val line = layout.getLineForOffset(active.displayStart.coerceIn(0, max(0, layout.text.length - 1)))
        val target = max(0f, line * layout.height.toFloat() / max(1, layout.lineCount) - layout.height * .22f)
        val t = ((timeUs - paragraph.startUs).coerceAtLeast(0L).toDouble() / max(1L, paragraph.endUs - paragraph.startUs)).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return -target.toFloat() * eased.toFloat()
    }

    private fun layoutInfo(canvas: Canvas, paragraph: VideoParagraph, settings: TtsVideoVisualSettings, rect: RectF, timeUs: Long): RenderedLayoutInfo {
        val size = fitTextSize(paragraph.displayText, settings, rect.width(), rect.height())
        val layout = makeLayout(paragraph.displayText, size, settings, rect.width().roundToInt(), Typeface.SANS_SERIF)
        return RenderedLayoutInfo(RectF(rect.centerX() - layout.width / 2f, rect.centerY() - layout.height / 2f, rect.centerX() + layout.width / 2f, rect.centerY() + layout.height / 2f), size, scrollOffset(layout, paragraph, settings, timeUs))
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, overridePaint: Paint = paint, destination: RectF = RectF(0f, 0f, settingsWidth(canvas), settingsHeight(canvas))) {
        val sourceRatio = bitmap.width.toFloat() / max(1, bitmap.height)
        val dstRatio = destination.width() / max(1f, destination.height())
        val src = if (sourceRatio > dstRatio) {
            val w = (bitmap.height * dstRatio).roundToInt().coerceAtMost(bitmap.width)
            RectF((bitmap.width - w) / 2f, 0f, (bitmap.width + w) / 2f, bitmap.height.toFloat())
        } else {
            val h = (bitmap.width / dstRatio).roundToInt().coerceAtMost(bitmap.height)
            RectF(0f, (bitmap.height - h) / 2f, bitmap.width.toFloat(), (bitmap.height + h) / 2f)
        }
        canvas.drawBitmap(bitmap, android.graphics.RectF(src).toRect(), destination, overridePaint)
    }

    private fun settingsWidth(canvas: Canvas) = canvas.width.toFloat()
    private fun settingsHeight(canvas: Canvas) = canvas.height.toFloat()
}

private fun RectF.toRect(): android.graphics.Rect = android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
