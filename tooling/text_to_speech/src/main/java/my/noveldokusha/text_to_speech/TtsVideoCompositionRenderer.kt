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
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.InputStream

/** Immutable chapter-facing artwork/background snapshot; image bytes never live in preferences. */
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
        val margin = settings.safeMarginPx.coerceIn(0f, min(settings.width, settings.height).toFloat() / 3f)
        val canvas = RectF(margin, margin, settings.width - margin, settings.height - margin)
        val art = mutableListOf<RectF>()
        if (settings.artworkMode != ArtworkMode.NONE && settings.artworkUris.isNotEmpty() && !settings.artworkOverlay) {
            val requestedWidth = settings.artworkWidthPx.coerceIn(120f, settings.width * .35f)
            when (settings.artworkMode) {
                ArtworkMode.LEFT -> {
                    val w = min(requestedWidth, max(0f, canvas.width() - 180f))
                    if (w > 0f) {
                        art += RectF(canvas.left, canvas.top, canvas.left + w, canvas.bottom)
                        canvas.left += w + 28f
                    }
                }
                ArtworkMode.RIGHT -> {
                    val w = min(requestedWidth, max(0f, canvas.width() - 180f))
                    if (w > 0f) {
                        art += RectF(canvas.right - w, canvas.top, canvas.right, canvas.bottom)
                        canvas.right -= w + 28f
                    }
                }
                ArtworkMode.BOTH -> {
                    val each = min(requestedWidth, max(0f, (canvas.width() - 90f) / 2f))
                    if (each > 0f) {
                        art += RectF(canvas.left, canvas.top, canvas.left + each, canvas.bottom)
                        art += RectF(canvas.right - each, canvas.top, canvas.right, canvas.bottom)
                        canvas.left += each + 28f
                        canvas.right -= each + 28f
                    }
                }
                ArtworkMode.NONE -> Unit
            }
        }
        val maxWidth = settings.width * settings.maxTextWidthFraction.coerceIn(.25f, 1f)
        if (canvas.width() > maxWidth) {
            val extra = (canvas.width() - maxWidth) / 2f
            canvas.left += extra
            canvas.right -= extra
        }
        if (canvas.width() < 64f) {
            val center = canvas.centerX()
            canvas.left = center - 32f
            canvas.right = center + 32f
        }
        return SafeTextRect(canvas, art)
    }

    fun render(
        canvas: Canvas,
        timeline: TtsVideoTimeline,
        settings: TtsVideoVisualSettings,
        snapshot: TtsVideoVisualSnapshot,
        timeUs: Long,
    ): RenderedLayoutInfo? {
        require(settings.width > 0 && settings.height > 0 && settings.fps > 0)
        canvas.save()
        try {
            drawBackground(canvas, settings, snapshot, timeline.durationUs, timeUs)
            drawArtwork(canvas, settings, snapshot)
            val current = timeline.paragraphAt(timeUs) ?: return null
            val safe = safeTextRect(settings)
            val visible = selectVisibleParagraphs(timeline, current, settings, safe.rect.height())
            drawParagraphStack(canvas, visible, current, settings, snapshot, safe.rect, timeUs)
            return layoutInfo(canvas, current, settings, safe.rect, timeUs, snapshot.textStyle)
        } finally {
            canvas.restore()
        }
    }

    private fun drawBackground(
        canvas: Canvas,
        settings: TtsVideoVisualSettings,
        snapshot: TtsVideoVisualSnapshot,
        durationUs: Long,
        timeUs: Long,
    ) {
        when (settings.backgroundMode) {
            BackgroundMode.SOLID, BackgroundMode.PRESET -> canvas.drawColor(settings.backgroundColor)
            BackgroundMode.IMAGE -> if (snapshot.backgroundBitmap != null) {
                drawCover(canvas, snapshot.backgroundBitmap)
            } else canvas.drawColor(settings.backgroundColor)
        }
        if (!settings.slideshowEnabled || snapshot.artworkBitmaps.isEmpty() || durationUs <= 0) return
        val state = slideshowState(snapshot.artworkBitmaps.size, settings, durationUs, timeUs)
        val current = snapshot.artworkBitmaps[state.index]
        val next = snapshot.artworkBitmaps[state.nextIndex]
        when (settings.slideshowTransition) {
            SlideshowTransition.NONE -> drawWithAlpha(canvas, current, .30f)
            SlideshowTransition.FADE -> {
                drawWithAlpha(canvas, current, .30f * (1f - state.transitionProgress))
                drawWithAlpha(canvas, next, .30f * state.transitionProgress)
            }
            SlideshowTransition.CROSSFADE -> {
                drawWithAlpha(canvas, current, .30f * (1f - state.transitionProgress))
                drawWithAlpha(canvas, next, .30f * state.transitionProgress)
            }
            SlideshowTransition.SUBTLE_SLIDE -> {
                drawWithAlpha(canvas, current, .30f)
                canvas.save()
                try {
                    canvas.translate((1f - state.transitionProgress) * canvas.width, 0f)
                    drawWithAlpha(canvas, next, .30f)
                } finally { canvas.restore() }
            }
            SlideshowTransition.SUBTLE_ZOOM -> {
                drawWithAlpha(canvas, current, .30f)
                canvas.save()
                try {
                    val scale = 1.02f + .03f * state.transitionProgress
                    canvas.scale(scale, scale, canvas.width / 2f, canvas.height / 2f)
                    drawWithAlpha(canvas, next, .18f * state.transitionProgress)
                } finally { canvas.restore() }
            }
        }
    }

    private data class SlideshowState(val index: Int, val nextIndex: Int, val transitionProgress: Float)

    private fun slideshowState(size: Int, settings: TtsVideoVisualSettings, durationUs: Long, timeUs: Long): SlideshowState {
        val safeSize = max(1, size)
        val rawSlot = when (settings.slideshowIntervalMode) {
            SlideshowIntervalMode.FIXED_INTERVAL -> max(1L, settings.slideshowIntervalMs)
            SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION -> max(1L, durationUs / safeSize)
            SlideshowIntervalMode.RANDOM_INTERVAL -> deterministicRandomInterval(settings.slideshowSeed, max(1L, timeUs / max(1L, settings.slideshowIntervalMs)))
        }
        val slotIndex = (timeUs / rawSlot).coerceAtLeast(0L)
        val slotStart = slotIndex * rawSlot
        val phase = ((timeUs - slotStart).coerceAtLeast(0L).toDouble() / rawSlot.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val transitionWindow = .18f
        val progress = if (phase >= 1f - transitionWindow) {
            ((phase - (1f - transitionWindow)) / transitionWindow).coerceIn(0f, 1f)
        } else 0f
        val index = (slotIndex % safeSize).toInt()
        return SlideshowState(index, (index + 1) % safeSize, progress)
    }

    private fun deterministicRandomInterval(seed: Long, slot: Long): Long {
        var x = seed xor (slot * -7046029254386353131L)
        x = x xor (x ushr 30); x *= -4658895280553007687L
        x = x xor (x ushr 27); x *= -7723592293110705685L
        x = x xor (x ushr 31)
        return 4_000L + abs(x % 8_001L)
    }

    private fun drawWithAlpha(canvas: Canvas, bitmap: Bitmap, alpha: Float, destination: RectF = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())) {
        paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        drawCover(canvas, bitmap, paint, destination)
        paint.alpha = 255
    }

    private fun drawArtwork(canvas: Canvas, settings: TtsVideoVisualSettings, snapshot: TtsVideoVisualSnapshot) {
        if (snapshot.artworkBitmaps.isEmpty() || settings.artworkMode == ArtworkMode.NONE) return
        val safe = safeTextRect(settings)
        val count = when (settings.artworkMode) {
            ArtworkMode.BOTH -> min(2, snapshot.artworkBitmaps.size)
            else -> min(1, snapshot.artworkBitmaps.size)
        }
        for (index in 0 until count) {
            val bitmap = snapshot.artworkBitmaps[index]
            val bounds = safe.artworkRects.getOrNull(index) ?: continue
            canvas.save()
            try {
                val clip = Path().apply {
                    addRoundRect(bounds, settings.artworkCornerRadiusPx, settings.artworkCornerRadiusPx, Path.Direction.CW)
                }
                canvas.clipPath(clip)
                drawWithAlpha(canvas, bitmap, settings.artworkOpacity, bounds)
                if (settings.artworkBorderWidthPx > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = settings.artworkBorderWidthPx
                    paint.color = settings.artworkBorderColor
                    canvas.drawRoundRect(bounds, settings.artworkCornerRadiusPx, settings.artworkCornerRadiusPx, paint)
                    paint.style = Paint.Style.FILL
                }
            } finally { canvas.restore() }
        }
    }

    private fun selectVisibleParagraphs(
        timeline: TtsVideoTimeline,
        current: VideoParagraph,
        settings: TtsVideoVisualSettings,
        height: Float,
    ): List<VideoParagraph> {
        if (settings.paragraphMode == ParagraphDisplayMode.CURRENT_ONLY) return listOf(current)
        val candidates = timeline.paragraphs
            .filter { it.id != current.id }
            .sortedBy { abs(it.blockIndex - current.blockIndex) }
        val contextScale = .78f
        val estimatedPerContext = (settings.fontSizePx * contextScale * settings.lineSpacingMultiplier * 2.2f + settings.cardPaddingPx * 2f + settings.paragraphSpacingPx)
            .coerceAtLeast(80f)
        val maxContexts = max(0, ((height - 160f) / estimatedPerContext).toInt().coerceAtMost(8))
        val allowed = when (settings.paragraphMode) {
            ParagraphDisplayMode.CURRENT_WITH_CONTEXT -> maxContexts.coerceAtMost(2)
            ParagraphDisplayMode.DYNAMIC_CONTEXT -> maxContexts
            ParagraphDisplayMode.CURRENT_ONLY -> 0
        }
        val chosen = candidates.take(allowed)
            .sortedWith(compareBy<VideoParagraph>({ it.startUs < current.startUs }, { abs(it.blockIndex - current.blockIndex) }, { it.startUs }))
        return (chosen + current).distinctBy { it.id }.sortedBy { it.startUs }
    }

    private data class ParagraphLayout(val paragraph: VideoParagraph, val layout: StaticLayout, val card: RectF, val isCurrent: Boolean)

    private fun drawParagraphStack(
        canvas: Canvas,
        paragraphs: List<VideoParagraph>,
        current: VideoParagraph,
        settings: TtsVideoVisualSettings,
        snapshot: TtsVideoVisualSnapshot,
        rect: RectF,
        timeUs: Long,
    ) {
        val layouts = paragraphs.map { paragraph ->
            val isCurrent = paragraph.id == current.id
            val size = if (isCurrent) fitTextSize(paragraph.displayText, settings, rect.width(), rect.height()) else settings.fontSizePx * .78f
            val layout = makeLayout(paragraph.displayText, size, settings, rect.width().roundToInt(), snapshot.textStyle)
            val card = RectF(0f, 0f, layout.width.toFloat() + settings.cardPaddingPx * 2f, layout.height.toFloat() + settings.cardPaddingPx * 2f)
            ParagraphLayout(paragraph, layout, card, isCurrent)
        }
        val gap = settings.paragraphSpacingPx.coerceAtLeast(12f)
        val totalHeight = layouts.sumOf { it.card.height().toDouble() }.toFloat() + gap * max(0, layouts.size - 1)
        val currentCenterY = rect.centerY()
        val currentIndex = layouts.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
        val yPositions = FloatArray(layouts.size)
        var y = currentCenterY - layouts[currentIndex].card.height() / 2f
        yPositions[currentIndex] = y
        for (i in currentIndex - 1 downTo 0) {
            y -= gap + layouts[i].card.height()
            yPositions[i] = y
        }
        y = currentCenterY + layouts[currentIndex].card.height() / 2f
        for (i in currentIndex + 1 until layouts.size) {
            y += gap
            yPositions[i] = y
            y += layouts[i].card.height()
        }
        val shift = when {
            totalHeight <= rect.height() -> 0f
            yPositions.first() < rect.top -> rect.top - yPositions.first()
            yPositions.last() + layouts.last().card.height() > rect.bottom -> rect.bottom - (yPositions.last() + layouts.last().card.height())
            else -> 0f
        }
        layouts.forEachIndexed { index, item ->
            drawParagraph(canvas, item, settings, rect, yPositions[index] + shift, timeUs)
        }
    }

    private fun drawParagraph(
        canvas: Canvas,
        item: ParagraphLayout,
        settings: TtsVideoVisualSettings,
        rect: RectF,
        top: Float,
        timeUs: Long,
    ) {
        val scale = if (item.isCurrent) 1f else .88f
        val alpha = if (item.isCurrent) 1f else .62f
        canvas.save()
        try {
            canvas.translate(rect.centerX(), top + item.card.height() / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-item.card.width() / 2f, -item.card.height() / 2f)
            paint.alpha = (alpha * settings.cardAlpha * 255).roundToInt().coerceIn(0, 255)
            if (settings.cardEnabled) {
                paint.style = Paint.Style.FILL
                paint.color = settings.cardColor
                canvas.drawRoundRect(item.card, settings.cardCornerRadiusPx, settings.cardCornerRadiusPx, paint)
                if (settings.cardStrokeWidthPx > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = settings.cardStrokeWidthPx
                    paint.color = settings.cardStrokeColor
                    canvas.drawRoundRect(item.card, settings.cardCornerRadiusPx, settings.cardCornerRadiusPx, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            canvas.translate(settings.cardPaddingPx, settings.cardPaddingPx)
            if (item.isCurrent && settings.longParagraphMode == LongParagraphMode.SMOOTH_SCROLL) {
                canvas.save()
                try {
                    val viewport = RectF(0f, 0f, item.layout.width.toFloat(), item.layout.height.toFloat())
                    canvas.clipRect(viewport)
                    canvas.translate(0f, scrollOffset(item.layout, item.paragraph, settings, timeUs))
                    item.layout.draw(canvas)
                    drawHighlight(canvas, item.layout, item.paragraph, settings, timeUs)
                } finally { canvas.restore() }
            } else {
                item.layout.draw(canvas)
                if (item.isCurrent) drawHighlight(canvas, item.layout, item.paragraph, settings, timeUs)
            }
        } finally {
            paint.alpha = 255
            canvas.restore()
        }
    }

    private fun drawHighlight(canvas: Canvas, layout: StaticLayout, paragraph: VideoParagraph, settings: TtsVideoVisualSettings, timeUs: Long) {
        val range = paragraph.spokenRanges.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs } ?: return
        val start = range.displayStart.coerceIn(0, layout.text.length)
        val end = range.displayEnd.coerceIn(start, layout.text.length)
        if (end <= start) return
        path.reset()
        layout.getSelectionPath(start, end, path)
        paint.color = settings.highlightColor
        paint.alpha = (settings.highlightAlpha * 255).roundToInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
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
        val minLayout = makeLayout(text, minSize, settings, width.roundToInt(), Typeface.SANS_SERIF)
        if (minLayout.height <= height * .9f) return minSize
        return minSize
    }

    private fun makeLayout(text: String, size: Float, settings: TtsVideoVisualSettings, width: Int, typeface: Typeface): StaticLayout {
        textPaint.textSize = size
        textPaint.color = settings.textColor
        textPaint.typeface = typeface
        textPaint.isAntiAlias = true
        textPaint.letterSpacing = settings.letterSpacingEm
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .setLineSpacing(settings.paragraphSpacingPx, settings.lineSpacingMultiplier)
            .build()
    }

    private fun scrollOffset(layout: StaticLayout, paragraph: VideoParagraph, settings: TtsVideoVisualSettings, timeUs: Long): Float {
        if (settings.longParagraphMode != LongParagraphMode.SMOOTH_SCROLL || paragraph.spokenRanges.isEmpty()) return 0f
        val active = paragraph.spokenRanges.firstOrNull { timeUs in it.startUs until it.endUs } ?: return 0f
        val line = layout.getLineForOffset(active.displayStart.coerceIn(0, max(0, layout.text.length - 1)))
        val lineHeight = if (layout.lineCount > 0) layout.height.toFloat() / layout.lineCount else 0f
        val target = max(0f, line * lineHeight - layout.height * .22f)
        val t = ((timeUs - paragraph.startUs).coerceAtLeast(0L).toDouble() / max(1L, paragraph.endUs - paragraph.startUs)).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return -target.toFloat() * eased.toFloat()
    }

    private fun layoutInfo(
        canvas: Canvas,
        paragraph: VideoParagraph,
        settings: TtsVideoVisualSettings,
        rect: RectF,
        timeUs: Long,
        typeface: Typeface,
    ): RenderedLayoutInfo {
        val size = fitTextSize(paragraph.displayText, settings, rect.width(), rect.height())
        val layout = makeLayout(paragraph.displayText, size, settings, rect.width().roundToInt(), typeface)
        return RenderedLayoutInfo(
            RectF(
                rect.centerX() - layout.width / 2f,
                rect.centerY() - layout.height / 2f,
                rect.centerX() + layout.width / 2f,
                rect.centerY() + layout.height / 2f,
            ),
            size,
            scrollOffset(layout, paragraph, settings, timeUs),
        )
    }

    private fun drawCover(
        canvas: Canvas,
        bitmap: Bitmap,
        overridePaint: Paint = paint,
        destination: RectF = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
    ) {
        val sourceRatio = bitmap.width.toFloat() / max(1, bitmap.height)
        val dstRatio = destination.width() / max(1f, destination.height())
        val src = if (sourceRatio > dstRatio) {
            val w = (bitmap.height * dstRatio).roundToInt().coerceAtMost(bitmap.width)
            android.graphics.Rect(
                ((bitmap.width - w) / 2).coerceAtLeast(0),
                0,
                ((bitmap.width + w) / 2).coerceAtMost(bitmap.width),
                bitmap.height,
            )
        } else {
            val h = (bitmap.width / max(0.0001f, dstRatio)).roundToInt().coerceAtMost(bitmap.height)
            android.graphics.Rect(
                0,
                ((bitmap.height - h) / 2).coerceAtLeast(0),
                bitmap.width,
                ((bitmap.height + h) / 2).coerceAtMost(bitmap.height),
            )
        }
        canvas.drawBitmap(bitmap, src, destination, overridePaint)
    }
}
