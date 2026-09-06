package my.noveldokusha.tooling.application_workers.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal class CinematicFrameRenderer(timelineFile: File) {
    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 24
        private const val CARD_W = 1545f
        private const val CARD_MIN_H = 345f
        private const val CARD_MAX_H = 593f
        private const val CARD_X = (WIDTH - CARD_W) / 2f
        private const val PAD_X = 93f
        private const val PAD_Y = 63f
        private const val CONTENT_TOP = 262f
        private const val CONTENT_BOTTOM = 952f
        private const val BODY_TEXT_MAX = 46f
        private const val BODY_TEXT_MIN = 36f
        private const val BODY_LINE_SPACING = 1.52f
    }

    private data class Range(
        val startChar: Int,
        val endChar: Int,
        val startMs: Int,
        val endMs: Int?,
    )

    private data class Paragraph(
        val index: Int,
        val label: String,
        val text: String,
        val startChar: Int,
        val endChar: Int,
        val startMs: Int,
        val endMs: Int,
        val ranges: List<Range>,
        val layout: StaticLayout,
        val cardY: Float,
        val cardH: Float,
    )

    private data class Star(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val radius: Float,
        val alpha: Float,
        val phase: Float,
    )

    private val title: String
    private val chapter: String
    private val durationMs: Long
    private val paragraphs: List<Paragraph>
    private val frameBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frameBitmap)

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.rgb(229, 231, 241)
        textSize = BODY_TEXT_MAX
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.rgb(247, 248, 255)
        textSize = 45f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val chapterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.argb(242, 176, 184, 224)
        textSize = 32f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.argb(190, 144, 151, 182)
        textSize = 23f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(102, 230, 132, 28)
        style = Paint.Style.FILL
    }
    private val highlightStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 255, 205, 125)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val selectionPath = Path()
    private val selectionBounds = RectF()
    private val cardRect = RectF()
    private val innerCardRect = RectF()
    private val highlightRect = RectF()
    private val footerTrackRect = RectF()
    private val slowStars: List<Star>
    private val mediumStars: List<Star>
    private val fastStars: List<Star>
    private val rockPaths: List<Path>
    private val rockSpeeds: FloatArray
    private val backgroundGradient = LinearGradient(
        0f,
        0f,
        WIDTH.toFloat(),
        HEIGHT.toFloat(),
        intArrayOf(Color.rgb(4, 5, 14), Color.rgb(14, 17, 39), Color.rgb(5, 8, 20)),
        floatArrayOf(0f, 0.56f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val vignetteGradient = RadialGradient(
        WIDTH / 2f,
        HEIGHT / 2f,
        930f,
        intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
        floatArrayOf(0.63f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val nebulaShaders = listOf(
        RadialGradient(430f, 370f, 520f, intArrayOf(Color.argb(92, 42, 33, 90), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP),
        RadialGradient(1190f, 260f, 630f, intArrayOf(Color.argb(92, 24, 51, 106), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP),
        RadialGradient(980f, 820f, 720f, intArrayOf(Color.argb(92, 37, 31, 90), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP),
    )
    private val nebulaRects = arrayOf(
        RectF(430f - 520f, 370f - 370f, 430f + 520f, 370f + 370f),
        RectF(1190f - 630f, 260f - 300f, 1190f + 630f, 260f + 300f),
        RectF(980f - 720f, 820f - 250f, 980f + 720f, 820f + 250f),
    )

    private val rocks = listOf(
        floatArrayOf(1060f, 90f, -7.5f, 1.1f, 11f, 18f),
        floatArrayOf(180f, 580f, 6f, -0.7f, -9f, 12f),
        floatArrayOf(1160f, 505f, -4f, 0.5f, 7f, 9f),
    )

    init {
        val root = JSONObject(timelineFile.readText())
        val chapterObj = root.optJSONObject("chapter") ?: JSONObject()
        val audioObj = root.optJSONObject("audio") ?: JSONObject()
        title = chapterObj.optString("novelTitle", "")
        chapter = chapterObj.optString("chapterTitle", "")
        durationMs = audioObj.optLong("durationMs", 0L)
        val rawParagraphs = root.optJSONArray("paragraphs") ?: error("Missing paragraphs")
        val prepared = ArrayList<Paragraph>(rawParagraphs.length())

        for (i in 0 until rawParagraphs.length()) {
            val p = rawParagraphs.getJSONObject(i)
            val text = p.optString("text", "")
            val size = findTextSize(text)
            val paragraphPaint = TextPaint(bodyPaint).apply { textSize = size }
            val layout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                paragraphPaint,
                (CARD_W - 2f * PAD_X).toInt(),
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, BODY_LINE_SPACING)
                .build()

            val textH = layout.height.toFloat()
            val cardH = (textH + 2f * PAD_Y).coerceIn(CARD_MIN_H, CARD_MAX_H)
            val cardY = CONTENT_TOP + (CONTENT_BOTTOM - CONTENT_TOP - cardH) / 2f
            val rangesArray = p.optJSONArray("ranges")
            val ranges = buildList {
                if (rangesArray != null) {
                    for (r in 0 until rangesArray.length()) {
                        val raw = rangesArray.getJSONObject(r)
                        val start = raw.optInt("startChar", 0)
                        val end = raw.optInt("endChar", start)
                        val startMs = raw.optInt("startMs", 0)
                        val endMs = if (raw.has("endMs") && !raw.isNull("endMs")) raw.optInt("endMs") else null
                        if (end > start) add(Range(start, end, startMs, endMs))
                    }
                }
            }.sortedBy { it.startMs }

            prepared += Paragraph(
                index = p.optInt("index", i),
                label = "PARAGRAPH ${String.format("%02d", p.optInt("index", i) + 1)} / ${String.format("%02d", rawParagraphs.length())}",
                text = text,
                startChar = p.optInt("startChar", 0),
                endChar = p.optInt("endChar", text.length),
                startMs = p.optInt("startMs", 0),
                endMs = p.optInt("endMs", durationMs.toInt()),
                ranges = ranges,
                layout = layout,
                cardY = cardY,
                cardH = cardH,
            )
        }

        paragraphs = prepared.sortedBy { it.startMs }
        slowStars = makeStars(230, 0.2f, 0.6f, 1.4f, 0.8f, 0.55f, 1.35f, 35f, 110f)
        mediumStars = makeStars(115, 0.6f, 1f, 5.5f, 2.3f, 0.8f, 1.9f, 75f, 180f)
        fastStars = makeFastStars()
        rockPaths = rocks.map { rock ->
            val radius = rock[5]
            Path().apply {
                for (i in 0 until 10) {
                    val angle = i * Math.PI * 2.0 / 10.0
                    val rr = radius * (0.74f + ((i * 37) % 31) / 100f)
                    val px = cos(angle).toFloat() * rr
                    val py = sin(angle).toFloat() * rr
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
        }
        rockSpeeds = rocks.map { it[4] }.toFloatArray()
    }

    fun durationSeconds(): Double = durationMs.coerceAtLeast(0L) / 1000.0

    fun frameAt(frameIndex: Int): Bitmap {
        val t = frameIndex.toDouble() / FPS
        val tMs = (t * 1000.0).toInt()
        drawBackground(t)
        drawHeader()
        val paragraph = findParagraph(tMs)
        if (paragraph != null) {
            drawCard(paragraph, tMs)
            drawFooter(paragraph, tMs)
        }
        return frameBitmap
    }

    private fun findTextSize(text: String): Float {
        for (size in BODY_TEXT_MAX.toInt() downTo BODY_TEXT_MIN.toInt()) {
            val paint = TextPaint(bodyPaint).apply { textSize = size.toFloat() }
            val layout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                paint,
                (CARD_W - 2f * PAD_X).toInt(),
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, BODY_LINE_SPACING)
                .build()
            if (layout.height <= (CARD_MAX_H - 2f * PAD_Y).toInt()) return size.toFloat()
        }
        return 32f
    }

    private fun makeStars(
        count: Int,
        minD: Float,
        maxD: Float,
        maxVx: Float,
        maxVy: Float,
        minR: Float,
        maxR: Float,
        minA: Float,
        maxA: Float,
    ): List<Star> {
        val random = Random(20260903)
        return List(count) {
            val d = minD + random.nextFloat() * (maxD - minD)
            Star(
                random.nextFloat() * WIDTH,
                random.nextFloat() * HEIGHT,
                (-maxVx + random.nextFloat() * 2f * maxVx) * d,
                (-maxVy + random.nextFloat() * 2f * maxVy) * d,
                (minR + random.nextFloat() * (maxR - minR)) * d / maxD,
                minA + random.nextFloat() * (maxA - minA),
                random.nextFloat() * Math.PI.toFloat() * 2f,
            )
        }
    }

    private fun makeFastStars(): List<Star> {
        val random = Random(2026090307)
        return List(7) {
            val a = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = 150f + random.nextFloat() * 170f
            Star(
                random.nextFloat() * WIDTH,
                random.nextFloat() * HEIGHT,
                cos(a) * speed,
                sin(a) * speed,
                12f + random.nextFloat() * 26f,
                65f + random.nextFloat() * 60f,
                random.nextFloat() * 25f,
            )
        }
    }

    private fun drawBackground(t: Double) {
        canvas.drawColor(Color.rgb(3, 4, 11))
        shapePaint.shader = backgroundGradient
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), shapePaint)
        shapePaint.shader = null

        for (i in nebulaShaders.indices) {
            shapePaint.shader = nebulaShaders[i]
            canvas.drawRect(nebulaRects[i], shapePaint)
        }
        shapePaint.shader = null

        for (star in slowStars) {
            val x = mod(star.x + star.vx * t.toFloat(), WIDTH.toFloat())
            val y = mod(star.y + star.vy * t.toFloat(), HEIGHT.toFloat())
            starPaint.color = Color.argb(
                (star.alpha * (0.82 + 0.18 * sin(t * 0.45 + star.phase))).toInt().coerceIn(0, 255),
                205,
                215,
                245,
            )
            canvas.drawCircle(x, y, star.radius, starPaint)
        }

        for (star in mediumStars) {
            val x = mod(star.x + star.vx * t.toFloat(), WIDTH.toFloat())
            val y = mod(star.y + star.vy * t.toFloat(), HEIGHT.toFloat())
            starPaint.color = Color.argb(
                (star.alpha * (0.76 + 0.24 * sin(t * 0.8 + star.phase))).toInt().coerceIn(0, 255),
                222,
                230,
                255,
            )
            canvas.drawCircle(x, y, star.radius, starPaint)
        }

        for (star in fastStars) {
            val cyc = mod(t + star.phase.toDouble(), 11.0)
            val x = mod(star.x + star.vx * cyc, WIDTH.toDouble() + 140.0).toFloat() - 70f
            val y = mod(star.y + star.vy * cyc, HEIGHT.toDouble() + 140.0).toFloat() - 70f
            val mag = sqrt(star.vx * star.vx + star.vy * star.vy)
            val ux = star.vx / mag
            val uy = star.vy / mag
            starPaint.color = Color.argb(
                (star.alpha * (0.65 + 0.35 * sin(t * 0.6 + star.phase))).toInt().coerceIn(0, 255),
                225,
                235,
                255,
            )
            starPaint.strokeWidth = 1f
            canvas.drawLine(x - ux * star.radius, y - uy * star.radius, x, y, starPaint)
            starPaint.color = Color.argb(220, 242, 246, 255)
            canvas.drawCircle(x, y, 1.5f, starPaint)
        }

        for (i in rocks.indices) {
            val r = rocks[i]
            val sx = mod(r[0] + r[2] * t.toFloat(), WIDTH.toFloat() + 100f) - 50f
            val sy = r[1] + r[3] * t.toFloat()
            canvas.save()
            canvas.translate(sx, sy)
            canvas.rotate(rockSpeeds[i] * t.toFloat())
            shapePaint.color = Color.argb(232, 62, 65, 77)
            shapePaint.style = Paint.Style.FILL
            canvas.drawPath(rockPaths[i], shapePaint)
            canvas.drawPath(rockPaths[i], highlightStrokePaint)
            canvas.restore()
        }

        if (t in 6.8..7.32) {
            val p = ease(((t - 6.8) / 0.52).toFloat())
            val sx = 1040f - 260f * p
            val sy = 165f + 125f * p
            shapePaint.color = Color.argb((190 * (1f - p)).toInt(), 236, 242, 255)
            shapePaint.strokeWidth = 2f
            canvas.drawLine(sx, sy, sx + 52f, sy - 25f, shapePaint)
        }

        shapePaint.shader = vignetteGradient
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), shapePaint)
        shapePaint.shader = null
    }

    private fun drawHeader() {
        canvas.drawText(title, WIDTH / 2f, 89f, titlePaint)
        canvas.drawText(chapter, WIDTH / 2f, 158f, chapterPaint)
        shapePaint.color = Color.argb(185, 129, 118, 233)
        shapePaint.strokeWidth = 3f
        canvas.drawLine(WIDTH / 2f - 90f, 189f, WIDTH / 2f + 90f, 189f, shapePaint)
        shapePaint.color = Color.argb(230, 219, 221, 255)
        canvas.drawCircle(WIDTH / 2f, 189f, 5f, shapePaint)
    }

    private fun drawCard(p: Paragraph, tMs: Int) {
        cardRect.set(CARD_X, p.cardY, CARD_X + CARD_W, p.cardY + p.cardH)
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = Color.argb(230, 7, 10, 21)
        canvas.drawRoundRect(cardRect, 29f, 29f, shapePaint)
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = 2f
        shapePaint.color = Color.argb(205, 134, 147, 226)
        canvas.drawRoundRect(cardRect, 29f, 29f, shapePaint)
        shapePaint.strokeWidth = 1f
        shapePaint.color = Color.argb(22, 255, 255, 255)
        innerCardRect.set(cardRect.left + 10f, cardRect.top + 10f, cardRect.right - 10f, cardRect.bottom - 10f)
        canvas.drawRoundRect(innerCardRect, 22f, 22f, shapePaint)
        shapePaint.style = Paint.Style.FILL

        val textTop = cardRect.top + (p.cardH - p.layout.height) / 2f
        val contentLeft = CARD_X + PAD_X
        canvas.save()
        canvas.translate(contentLeft, textTop)

        val active = activeRange(p, tMs)
        if (active != null) {
            val start = (active.startChar - p.startChar).coerceIn(0, p.text.length)
            val end = (active.endChar - p.startChar).coerceIn(start, p.text.length)
            if (end > start) {
                selectionPath.reset()
                p.layout.getSelectionPath(start, end, selectionPath)
                selectionPath.computeBounds(selectionBounds, true)
                if (!selectionBounds.isEmpty) {
                    highlightRect.set(
                        selectionBounds.left - 5f,
                        selectionBounds.top - 3f,
                        selectionBounds.right + 5f,
                        selectionBounds.bottom + 3f,
                    )
                    canvas.drawRoundRect(highlightRect, 9f, 9f, highlightPaint)
                    canvas.drawRoundRect(highlightRect, 9f, 9f, highlightStrokePaint)
                }
            }
        }

        p.layout.draw(canvas)
        canvas.restore()
    }

    private fun activeRange(p: Paragraph, tMs: Int): Range? {
        if (p.ranges.isEmpty()) return null
        var lo = 0
        var hi = p.ranges.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (p.ranges[mid].startMs <= tMs) lo = mid + 1 else hi = mid
        }
        val index = lo - 1
        if (index < 0) return null
        val range = p.ranges[index]
        val end = range.endMs ?: p.ranges.getOrNull(index + 1)?.startMs ?: p.endMs
        return if (tMs >= range.startMs && tMs < max(end, range.startMs + 1)) range else null
    }

    private fun drawFooter(p: Paragraph, tMs: Int) {
        canvas.drawText(p.label, 90f, 1009f, footerPaint)
        val bx = WIDTH - 477f
        val by = 996f
        val bw = 383f
        footerTrackRect.set(bx, by, bx + bw, by + 4f)
        shapePaint.color = Color.argb(155, 55, 61, 88)
        canvas.drawRect(footerTrackRect, shapePaint)
        val progress = if (durationMs > 0) (tMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        shapePaint.color = Color.argb(220, 142, 125, 238)
        canvas.drawRect(bx, by, bx + bw * progress.toFloat(), by + 4f, shapePaint)
    }

    private fun findParagraph(tMs: Int): Paragraph? {
        if (paragraphs.isEmpty()) return null
        var lo = 0
        var hi = paragraphs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (paragraphs[mid].startMs <= tMs) lo = mid + 1 else hi = mid
        }
        return paragraphs[(lo - 1).coerceAtLeast(0)]
    }

    private fun ease(x: Float) = x * x * (3f - 2f * x)

    private fun mod(x: Float, m: Float): Float {
        var v = x % m
        if (v < 0f) v += m
        return v
    }

    private fun mod(x: Double, m: Double): Double {
        var v = x % m
        if (v < 0.0) v += m
        return v
    }
}
