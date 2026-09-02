package my.noveldokusha.video_export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.ColorInt
import my.noveldokusha.reader_visuals.BackgroundLayer
import my.noveldokusha.reader_visuals.HighlightSpan
import my.noveldokusha.reader_visuals.ReaderVisualSnapshot
import java.io.File

/**
 * Рисует один кадр видео главы: фон (пресет/картинка), шапка главы, три абзаца
 * (prev превью / current карточка / next превью) и подсветка текущего слова.
 *
 * Кадр — чистая функция позиции семпла: [framePlan] детерминированно вычисляет
 * состояние конвейера, [renderFrame] его отрисовывает. Один и тот же рендер
 * одного и того же тайминга всегда даёт одинаковый кадр.
 *
 * Вся визуальная конфигурация берётся ТОЛЬКО из замороженного
 * [VideoStyleSnapshot] (через [VideoLayoutConfig]): типографика, цвета, карточка,
 * отступы, подсветка. Переданных «сбоку» цветов/параметров, обходящих стиль,
 * не существует. [ReaderVisualSnapshot] используется исключительно для фона и
 * как источник дефолтов для [VideoStyleSnapshot.defaultFor].
 */
class VideoFrameRenderer(
    private val snapshot: ReaderVisualSnapshot,
    private val timeline: VideoExportTimeline,
    private val typeface: Typeface,
    private val novelTitle: String = "",
    private val chapterTitle: String = "",
    private val backgroundImageDecoder: (File) -> Bitmap? = { null },
    private val backgroundFileResolver: (String) -> File? = { null },
    private val artworkImageDecoder: (String) -> Bitmap? = { null },
    private val videoStyle: VideoStyleSnapshot = VideoStyleSnapshot.defaultFor(snapshot),
) {

    data class CardColors(
        @ColorInt val fillArgb: Int,
        @ColorInt val strokeArgb: Int,
    ) {
        companion object {
            /** Блюпринт карточки из плана: #332A59B6 + светлая обводка. */
            fun blueprint() = CardColors(
                fillArgb = 0x332A59B6.toInt(),
                strokeArgb = 0x80FFFFFF.toInt(),
            )
        }
    }

    /**
     * Geometry of one conveyor slot on a given frame.
     *
     * [window] is a strict clip: a paragraph is never drawn outside its own
     * corridor window (the prev/current/next windows are pairwise disjoint).
     * [rect] is the final rectangle where the text stands (top already
     * includes any scroll offset).
     */
    data class SlotFrame(
        val paragraphIndex: Int,
        val rect: RectF,
        val window: RectF,
        val scale: Float,
        val alpha: Float,
        /** Window scroll inside the card for paragraphs taller than the card, px. */
        val scrollOffset: Float = 0f,
        /** Text range of the currently spoken word (current only). */
        val highlightWordRange: IntRange? = null,
    ) {
        /**
         * Transformed glyph bounds in canvas coordinates (after the scale
         * around the text-area center). Depends on the local origin
         * = (textX0 + layoutWidth/2, rect.top).
         */
        fun contentBounds(layoutWidth: Float, layoutHeight: Float, textX0: Float): RectF {
            val cx = textX0 + layoutWidth / 2f
            val left = cx + (0f - layoutWidth / 2f) * scale
            val width = layoutWidth * scale
            val top = rect.top
            val height = layoutHeight * scale
            return RectF(left, top, left + width, top + height)
        }
    }

    /** Детерминированное состояние кадра: что и где рисовать. */
    data class FramePlan(
        /** true — идёт озвучка названия главы: рисуется только титул. */
        val chapterIntro: Boolean = false,
        /** Старый prev (i-2), растворяющийся в переходе. */
        val fadingOut: SlotFrame? = null,
        val prev: SlotFrame? = null,
        val current: SlotFrame? = null,
        val next: SlotFrame? = null,
    )

    companion object {
        private const val HEADER_ALPHA = 0.5f
        private const val DEFAULT_BACKGROUND_COLOR = 0xFF15181D.toInt()

        /** Крупный шрифт титула во вступлении (после озвучки — обычная шапка). */
        const val TITLE_INTRO_FONT_PX = 64f
        const val TITLE_INTRO_MAX_LINES = 2
        const val TITLE_INTRO_CAPTION_FONT_PX = 30f

        /**
         * Разрешает цвета карточки из реальной темы приложения: заливка —
         * ?attr/colorAccentTransparent (coreui), обводка — ?attr/colorControlHighlight
         * (framework). Вызывается один раз на enqueue экспорта (результат
         * замораживается в [VideoStyleSnapshot]); при неудаче — блюпринт.
         */
        fun resolveThemeCardColors(context: Context): CardColors {
            val fillOut = android.util.TypedValue()
            val attrId = context.resources.getIdentifier(
                "colorAccentTransparent", "attr", context.packageName,
            )
            val fill = if (attrId != 0 && context.theme.resolveAttribute(attrId, fillOut, true)) {
                fillOut.data.takeUnless { it == 0 }
            } else {
                null
            } ?: CardColors.blueprint().fillArgb

            val strokeOut = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorControlHighlight, strokeOut, true)
            val stroke = strokeOut.data.takeUnless { it == 0 } ?: Color.LTGRAY

            return CardColors(fillArgb = fill, strokeArgb = stroke)
        }

        /**
         * Цвет текста для рендера: замороженный [ReaderVisualSnapshot.textColorArgb],
         * либо автоцвет по средней яркости фонового слоя (пресет/дефолт).
         * Делегирует общей логике [VideoStyleSnapshot.resolveDefaultTextColor].
         */
        fun resolveTextColor(
            snapshot: ReaderVisualSnapshot,
            defaultBackgroundArgb: Int = VIDEO_DEFAULT_BACKGROUND_ARGB,
        ): Int = VideoStyleSnapshot.resolveDefaultTextColor(snapshot, defaultBackgroundArgb)

        private const val VIDEO_DEFAULT_BACKGROUND_ARGB = 0xFF15181D.toInt()

        /**
         * Layout крупного титула во вступлении (общий с QA-тестом). Тот же
         * builder используется и при подсветке слов — rect'ы глифов точные.
         * Ширина текста берётся из активного макета, чтобы титул чтил поля.
         */
        fun buildTitleIntroLayout(
            text: String,
            typeface: Typeface,
            textColorArgb: Int,
            textWidthPx: Int = VideoLayoutSpec.CARD_TEXT_WIDTH,
        ): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                color = textColorArgb
                textSize = TITLE_INTRO_FONT_PX
            }
            return StaticLayout.Builder
                .obtain(text, 0, text.length, paint, textWidthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(TITLE_INTRO_MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
    }

    private val layoutConfig: VideoLayoutConfig = VideoLayoutConfig.from(videoStyle)

    /** Типографика стиля имеет приоритет над переданным извне шрифтом. */
    private val effectiveTypeface: Typeface = resolveVideoTypeface(typeface, videoStyle)

    private val layoutCache = ParagraphLayoutCache(effectiveTypeface, videoStyle.textColorArgb, layoutConfig)
    private val backgroundLayer: BackgroundLayer = snapshot.backgroundLayer(backgroundFileResolver)

    /** Slide-show scheduler (детерминированный по общей длительности аудио). */
    private val slideshow: SlideshowScheduler? =
        if (videoStyle.slideshowConfig.enabled && videoStyle.slideshowItems.isNotEmpty()) {
            val totalUs = timeline.totalSamples * 1_000_000L / timeline.sampleRate
            SlideshowScheduler(
                config = videoStyle.slideshowConfig,
                items = videoStyle.slideshowItems,
                totalAudioMs = totalUs / 1_000L,
            ).takeIf { !it.isEmpty }
        } else null

    /** Шрифт, реально используемый в рендере (учитывает bold/italic из стиля). */
    private fun resolveVideoTypeface(base: Typeface, style: VideoStyleSnapshot): Typeface {
        val flag = when {
            style.bold && style.italic -> Typeface.BOLD_ITALIC
            style.bold -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (flag == Typeface.NORMAL && style.fontFamily == snapshot.fontFamily) return base
        return runCatching { Typeface.create(style.fontFamily, flag) }.getOrDefault(base)
    }

    private val headerTextColor: Int =
    (videoStyle.textColorArgb and 0x00FFFFFF) or ((0xFF * HEADER_ALPHA).toInt() shl 24)

    private fun autofit(index: Int): Float =
        layoutCache.layoutFor(index, timeline.paragraphs[index].displayText).autofitScale

    /** Layout height for a paragraph (px, deterministic) — for QA transform-bounds checks. */
    fun layoutContentHeightFor(paragraphIndex: Int): Float =
        layoutCache.layoutFor(paragraphIndex, timeline.paragraphs[paragraphIndex].displayText)
            .layout.height.toFloat()

    /** Слово-подсветка текущего абзаца в момент [sample]. */
    private fun highlightRange(index: Int, sample: Long): IntRange? {
        val p = timeline.paragraphs.getOrNull(index) ?: return null
        val word = timeline.wordAtSample(sample, p) ?: return null
        if (word.displayRange.isEmpty()) return null
        return word.displayRange
    }

    private fun prevIndex(i: Int): Int? = if (i >= 1) i - 1 else null

    private fun nextIndex(i: Int): Int? =
        if (i < timeline.paragraphs.lastIndex) i + 1 else null

    /**
     * Состояние конвейера на кадре [sample]. Внутри перехода
     * (sample - start < [VideoLayoutConfig.transitionUs]) старый current уходит
     * в prev, следующий поднимается в current, новый следующий всплывает снизу.
     *
     * Режим презентации берётся из замороженного стиля:
     *  - CURRENT_ONLY — всегда только карточка текущего абзаца;
     *  - CURRENT_WITH_CONTEXT — классический конвейер (prev/current/next);
     *  - DYNAMIC_CONTEXT — контекст показывается, пока current помещается в
     *    карточку; при длинном current контекст убирается ДО уменьшения шрифта.
     */
    fun framePlan(sample: Long): FramePlan {
        val intro = timeline.title
        if (intro != null && sample < intro.endSample) {
            // Идёт озвучка названия главы — титульный кадр.
            return FramePlan(chapterIntro = true)
        }
        val ps = timeline.paragraphs
        if (ps.isEmpty()) return FramePlan()

        val i = ps.indexOf(timeline.paragraphAtSample(sample))
        val cardWindow = layoutConfig.cardWindow()

        // Top of the line containing the currently spoken word (in layout-space px).
        fun activeLineTopInLayout(layout: StaticLayout, sample: Long, p: ParagraphTiming): Float {
            val word = timeline.wordAtSample(sample, p) ?: return 0f
            if (word.displayRange.isEmpty()) return 0f
            val offset = word.displayRange.first.coerceIn(0, p.displayText.length)
            val line = layout.getLineForOffset(offset)
            return layout.getLineTop(line).toFloat()
        }

        // Marquee reveal for paragraphs taller than the card: instead of a
        // single screenful that silently clips the rest, the text scrolls in
        // lockstep with the currently SPOKEN LINE so the reading region stays
        // in view, and the bottom of the content settles exactly at the window
        // bottom by paragraph end (nothing cut at both ends at once).
        fun scrollOffsetFor(index: Int, scale: Float): Float {
            val p = ps[index]
            val layout = layoutCache.layoutFor(index, p.displayText).layout
            val fitted = layout.height.toFloat() * scale
            val windowH = cardWindow.height()
            val overflow = fitted - windowH
            if (overflow <= 0f) return 0f

            // Vertical position of the current line's top, scaled to the card.
            val currentLineY = activeLineTopInLayout(layout, sample, p) * scale

            // Desired scroll: bring the spoken line's top near the window top
            // (with a small lead so the next line peeks in), clamped so the
            // scroll can never exceed overflow (the last line ends at bottom).
            val leadLineHeight =
                if (layout.lineCount > 0) layout.getLineBottom(0) - layout.getLineTop(0)
                else 0f
            val lead = leadLineHeight.toFloat() * scale
            val desired = (currentLineY - lead).coerceAtLeast(0f)
            return desired.coerceAtMost(overflow)
        }

        fun currentOnly(): FramePlan {
            val curScroll = scrollOffsetFor(i, autofit(i))
            return FramePlan(
                current = SlotFrame(
                    paragraphIndex = i,
                    rect = RectF(
                        layoutConfig.textX0(), cardWindow.top - curScroll,
                        layoutConfig.textX0() + cardWindow.width(), cardWindow.bottom - curScroll,
                    ),
                    window = cardWindow,
                    scale = autofit(i),
                    alpha = 1f,
                    scrollOffset = curScroll,
                    highlightWordRange = highlightRange(i, sample),
                ),
            )
        }

        if (videoStyle.presentation == ParagraphPresentation.CURRENT_ONLY) return currentOnly()
        if (videoStyle.presentation == ParagraphPresentation.DYNAMIC_CONTEXT && autofit(i) < 1f) {
            return currentOnly()
        }

        val start = ps[i].startSample
        val rawT = if (sample >= start) {
            ((sample - start).toFloat() / layoutConfig.transitionUs).coerceIn(0f, 1f)
        } else {
            0f
        }
        val t = VideoLayoutSpec.smoothstep(rawT)

        val prevWindow = layoutConfig.prevWindow()
        val nextWindow = layoutConfig.nextWindow()
        val colL = layoutConfig.columnLeft()
        val colR = layoutConfig.columnRight()

        fun slotRect(top: Float, scale: Float, index: Int): RectF {
            val h = layoutCache.layoutFor(index, ps[index].displayText).layout.height.toFloat() * scale
            return RectF(colL, top, colR, top + h)
        }

        fun steady(): FramePlan {
            val curScroll = scrollOffsetFor(i, autofit(i))
            return FramePlan(
                prev = prevIndex(i)?.let { idx ->
                    SlotFrame(
                        paragraphIndex = idx,
                        rect = slotRect(layoutConfig.prevTop(), layoutConfig.previewScale, idx),
                        window = prevWindow,
                        scale = layoutConfig.previewScale,
                        alpha = layoutConfig.previewAlpha,
                    )
                },
                current = SlotFrame(
                    paragraphIndex = i,
                    rect = RectF(
                        layoutConfig.textX0(), cardWindow.top - curScroll,
                        layoutConfig.textX0() + cardWindow.width(), cardWindow.bottom - curScroll,
                    ),
                    window = cardWindow,
                    scale = autofit(i),
                    alpha = 1f,
                    scrollOffset = curScroll,
                    highlightWordRange = highlightRange(i, sample),
                ),
                next = nextIndex(i)?.let { idx ->
                    SlotFrame(
                        paragraphIndex = idx,
                        rect = slotRect(layoutConfig.nextTop(), layoutConfig.previewScale, idx),
                        window = nextWindow,
                        scale = layoutConfig.previewScale,
                        alpha = layoutConfig.previewAlpha,
                    )
                },
            )
        }

        // The first paragraph does not "float" in — the conveyor only crosses
        // paragraphs starting from the second one.
        if (i == 0 || rawT <= 0f) return steady()

        // Conveyor: every layer moves LINEARLY on rawT (equal speeds), but is
        // visible only inside ITS OWN corridor window. The prev/current/next
        // windows never overlap, so at any instant glyphs of different layers
        // cannot land on top of each other. The departing layer leaves upward
        // past the prev window; the incoming current climbs from the next
        // window through the card.
        val prevTop = layoutConfig.prevTop()
        val curFrom = layoutConfig.nextTop()
        val curTo = cardWindow.top
        val nextFrom = layoutConfig.nextPreRollTop()

        val fadingTop = prevTop - 160f * rawT
        val prevTopNow = curTo - (curTo - prevTop) * rawT
        val curTopNow = curFrom - (curFrom - curTo) * rawT
        val nextTopNow = nextFrom - (nextFrom - layoutConfig.nextTop()) * rawT

        val prevScaleNow = VideoLayoutSpec.lerp(autofit(prevIndex(i)!!), layoutConfig.previewScale, t)
        val curScaleNow = VideoLayoutSpec.lerp(layoutConfig.previewScale, autofit(i), t)
        val curScroll = scrollOffsetFor(i, curScaleNow)

        return FramePlan(
            fadingOut = if (i >= 2) {
                SlotFrame(
                    paragraphIndex = i - 2,
                    rect = slotRect(fadingTop, layoutConfig.previewScale, i - 2),
                    window = prevWindow,
                    scale = layoutConfig.previewScale,
                    alpha = layoutConfig.previewAlpha * (1f - t),
                )
            } else {
                null
            },
            prev = prevIndex(i)?.let { idx ->
                SlotFrame(
                    paragraphIndex = idx,
                    rect = slotRect(prevTopNow, prevScaleNow, idx),
                    window = prevWindow,
                    scale = prevScaleNow,
                    alpha = VideoLayoutSpec.lerp(1f, layoutConfig.previewAlpha, t),
                )
            },
            current = SlotFrame(
                paragraphIndex = i,
                rect = RectF(
                    layoutConfig.textX0(), curTopNow - curScroll,
                    layoutConfig.textX0() + cardWindow.width(), curTopNow - curScroll + cardWindow.height(),
                ),
                window = cardWindow,
                scale = curScaleNow,
                alpha = VideoLayoutSpec.lerp(layoutConfig.previewAlpha, 1f, t),
                scrollOffset = curScroll,
                highlightWordRange = highlightRange(i, sample),
            ),
            next = nextIndex(i)?.let { idx ->
                SlotFrame(
                    paragraphIndex = idx,
                    rect = slotRect(nextTopNow, layoutConfig.previewScale, idx),
                    window = nextWindow,
                    scale = layoutConfig.previewScale,
                    alpha = VideoLayoutSpec.lerp(0f, layoutConfig.previewAlpha, t),
                )
            },
        )
    }

    /** Рисует кадр [sample] на [canvas] (должен быть 1920×1080). */
    fun renderFrame(canvas: Canvas, sample: Long) {
        drawBackground(canvas)
        drawSlideshow(canvas, sample)
        drawSideArtwork(canvas)
        val plan = framePlan(sample)
        if (plan.chapterIntro) {
            drawChapterIntro(canvas, sample)
            return
        }
        drawHeader(canvas)
        plan.fadingOut?.let { drawSlot(canvas, it) }
        plan.prev?.let { drawSlot(canvas, it) }
        plan.next?.let { drawSlot(canvas, it) }
        plan.current?.let { drawCardSlot(canvas, it) }
    }

    // ── Slide-show (Phase G) ──────────────────────────────────────────────

    private fun slideBitmap(fileName: String, item: ArtworkItem): Bitmap? =
        artworkBitmaps.getOrPut(fileName) { artworkImageDecoder(fileName) }

    /** Рисует активный слайд (+ переход по слайдшоу-таймлайну). */
    private fun drawSlideshow(canvas: Canvas, sample: Long) {
        val s = slideshow ?: return
        val timeMs = sample / 1000L
        val frame = s.frameAt(timeMs)
        if (frame.itemIndex < 0) return
        val item = videoStyle.slideshowItems.getOrNull(frame.itemIndex) ?: return
        val bitmap = slideBitmap(item.fileName, item) ?: return

        val win = slideshowBounds(item)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.alpha = (0xFF * item.opacity.coerceIn(0f, 1f)).toInt()

        canvas.save()
        drawSlideBitmap(canvas, bitmap, win, item, paint)
        canvas.restore()

        // Crossfade: предыдущий слайд растворяется под новым (draw под ним).
        if (frame.transitioning && frame.fromIndex >= 0 && frame.fromIndex != frame.itemIndex) {
            val fromItem = videoStyle.slideshowItems.getOrNull(frame.fromIndex) ?: return
            val fromBmp = slideBitmap(fromItem.fileName, fromItem) ?: return
            val fadeIn = frame.progress.coerceIn(0f, 1f)
            val fromPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            fromPaint.alpha = ((0xFF * fromItem.opacity.coerceIn(0f, 1f)) * (1f - fadeIn)).toInt()
            drawSlideBitmap(canvas, fromBmp, slideshowBounds(fromItem), fromItem, fromPaint)
        }
    }

    private fun slideshowBounds(item: ArtworkItem): RectF {
        val w = layoutConfig.width.toFloat()
        val h = layoutConfig.height.toFloat()
        val slideW = w * item.size.coerceIn(0.1f, 1.2f)
        val slideH = slideW * 9f / 16f
        val bottom = h * item.position.coerceIn(0.2f, 1f)
        val top = (bottom - slideH).coerceAtLeast(0f)
        val x0 = (w - slideW) / 2f
        return RectF(x0, top, x0 + slideW, bottom)
    }

    private fun drawSlideBitmap(canvas: Canvas, bitmap: Bitmap, rect: RectF, item: ArtworkItem, paint: Paint) {
        val rect2 = RectF(rect)
        val scale = when (item.cropMode) {
            ArtworkFitMode.COVER -> maxOf(rect2.width() / bitmap.width, rect2.height() / bitmap.height)
            ArtworkFitMode.CONTAIN -> minOf(rect2.width() / bitmap.width, rect2.height() / bitmap.height)
        }
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val dx = rect2.centerX() - drawW / 2f
        val dy = rect2.centerY() - drawH / 2f
        val dest = RectF(dx, dy, dx + drawW, dy + drawH)
        val radius = item.cornerRadius.coerceAtLeast(0f)
        val path = Path().apply {
            addRoundRect(rect2, radius, radius, Path.Direction.CW)
        }
        val clip = canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, null, dest, paint)
        canvas.restoreToCount(clip)
        if (item.borderWidth > 0f) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = item.borderColorArgb
                style = Paint.Style.STROKE
                strokeWidth = item.borderWidth
            }
            canvas.drawRoundRect(rect2, radius, radius, border)
        }
        if (item.shadow) {
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x26000000.toInt()
            }
            val shadowRect = RectF(rect2.left + 8f, rect2.top + 14f, rect2.right + 8f, rect2.bottom + 14f)
            canvas.drawRoundRect(shadowRect, radius, radius, shadow)
        }
    }

    // ── Side artwork ─────────────────────────────────────────────────────

    private val artworkBitmaps = HashMap<String, Bitmap?>()

    private fun artworkBitmap(fileName: String): Bitmap? =
        artworkBitmaps.getOrPut(fileName) { artworkImageDecoder(fileName) }

    private fun drawSideArtwork(canvas: Canvas) {
        layoutConfig.leftArtwork?.let { drawArtwork(canvas, it, layoutConfig.leftArtworkX(), false) }
        layoutConfig.rightArtwork?.let { drawArtwork(canvas, it, layoutConfig.rightArtworkX(), true) }
    }

    private fun drawArtwork(canvas: Canvas, art: VideoArtwork, artW: Float, isRight: Boolean) {
        if (artW <= 0f) return
        val bitmap = artworkBitmap(art.fileName) ?: return
        val maxDrawH = art.heightCapFraction * layoutConfig.height.toFloat()
        val scale = when (art.fitMode) {
            ArtworkFitMode.COVER -> maxOf(artW / bitmap.width, maxDrawH / bitmap.height)
            ArtworkFitMode.CONTAIN -> minOf(artW / bitmap.width, maxDrawH / bitmap.height)
        }
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val bandH = layoutConfig.height.toFloat()
        val nominal = if (isRight) {
            RectF(layoutConfig.width.toFloat() - artW, 0f, layoutConfig.width.toFloat(), bandH)
        } else {
            RectF(0f, 0f, artW, bandH)
        }
        // Центрируем битмап внутри зоны арта; всё, что выходит за зону, клипается.
        val centX = (nominal.left + nominal.right) / 2f
        val x0 = centX - drawW / 2f
        val vFrac = when (art.verticalAlignment) {
            ArtworkVerticalAlignment.TOP -> 0f
            ArtworkVerticalAlignment.CENTER -> 0.5f
            ArtworkVerticalAlignment.BOTTOM -> 1f
        }
        val y0 = (bandH - drawH) * vFrac
        val rect = RectF(x0, y0, x0 + drawW, y0 + drawH)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (0xFF * art.opacity.coerceIn(0f, 1f)).toInt()
        }
        val radius = art.cornerRadius
        canvas.save()
        canvas.clipRect(nominal)
        if (radius > 0f) {
            val path = Path().apply {
                addRoundRect(nominal, radius, radius, Path.Direction.CW)
            }
            canvas.clipPath(path)
        }
        canvas.drawBitmap(bitmap, null, rect, paint)
        canvas.restore()
        if (art.borderWidth > 0f) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = art.borderColorArgb
                style = Paint.Style.STROKE
                strokeWidth = art.borderWidth
            }
            canvas.drawRoundRect(nominal, radius, radius, border)
        }
    }

    /**
     * Вступление: пока озвучивается название главы ([TitleTiming]) — крупный
     * титул по центру карточки с подсветкой текущего слова; после — обычная
     * приглушённая шапка и конвейер абзацев.
     */
    private fun drawChapterIntro(canvas: Canvas, sample: Long) {
        val title = timeline.title ?: return
        val layout = introLayout(title.displayText)

        canvas.save()
        canvas.translate(layoutConfig.textX0(), 0f)
        var yCursor = introTop(layout.height.toFloat())
        if (novelTitle.isNotBlank()) {
            drawIntroCaption(canvas, yCursor)
            yCursor += TITLE_INTRO_CAPTION_FONT_PX + 16f
        }

        canvas.save()
        canvas.translate(0f, yCursor)
        val range = title.wordAtSample(sample)?.displayRange
        if (range != null && !range.isEmpty()) {
            val rects = HighlightSpan.wordRects(
                layout, range.first, range.last + 1, layoutConfig.highlightPadding,
            )
            val hpaint = HighlightSpan.paint(layoutConfig.highlightColorArgb)
            hpaint.alpha = (0xFF * layoutConfig.highlightAlpha.coerceIn(0f, 1f)).toInt()
            for (rc in rects) {
                canvas.drawRoundRect(rc, layoutConfig.highlightRadius, layoutConfig.highlightRadius, hpaint)
            }
        }
        layout.draw(canvas)
        canvas.restore()

        canvas.restore()
    }

    /** Верх титульного блока: блок центрируется по вертикали карточки. */
    private fun introTop(titleHeight: Float): Float {
        val area = layoutConfig.cardRect()
        val block = titleHeight + (if (novelTitle.isNotBlank()) TITLE_INTRO_CAPTION_FONT_PX + 16f else 0f)
        return (area.top + (area.height() - block) / 2f).coerceAtLeast(area.top)
    }

    private fun drawIntroCaption(canvas: Canvas, top: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (headerTextColor and 0x00FFFFFF) or ((0xFF * HEADER_ALPHA).toInt() shl 24)
            textSize = TITLE_INTRO_CAPTION_FONT_PX
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(novelTitle, layoutConfig.cardTextWidth() / 2f, top + TITLE_INTRO_CAPTION_FONT_PX, paint)
    }

    /** Layout титула (кэшируется — титул не меняется в течение кадра/экспорта). */
    private var introLayoutCache: StaticLayout? = null

    private fun introLayout(text: String): StaticLayout {
        introLayoutCache?.let { return it }
        val layout = buildTitleIntroLayout(
            text,
            effectiveTypeface,
            headerTextColor,
            layoutConfig.cardTextWidth().toInt(),
        )
        introLayoutCache = layout
        return layout
    }

    private fun drawBackground(canvas: Canvas) {
        when (val layer = backgroundLayer) {
            BackgroundLayer.None -> canvas.drawColor(DEFAULT_BACKGROUND_COLOR)
            is BackgroundLayer.Preset -> {
                val gradient = LinearGradient(
                    0f, 0f, 0f, layoutConfig.height.toFloat(),
                    layer.preset.colors.toIntArray(), null, Shader.TileMode.CLAMP,
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
                canvas.drawRect(
                    0f, 0f,
                    layoutConfig.width.toFloat(), layoutConfig.height.toFloat(),
                    paint,
                )
            }
            is BackgroundLayer.Image -> {
                val bitmap = backgroundImageDecoder(layer.file)
                if (bitmap != null) {
                    drawBackgroundImage(canvas, bitmap)
                } else {
                    canvas.drawColor(DEFAULT_BACKGROUND_COLOR)
                }
            }
        }
    }

    private fun drawBackgroundImage(canvas: Canvas, bitmap: Bitmap) {
        val vw = layoutConfig.width.toFloat()
        val vh = layoutConfig.height.toFloat()
        val scale = maxOf(vw / bitmap.width, vh / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (vw - drawW) / 2f
        val top = (vh - drawH) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
    }

    private fun drawHeader(canvas: Canvas) {
        val title = buildString {
            if (novelTitle.isNotBlank()) {
                append(novelTitle)
                if (chapterTitle.isNotBlank()) append(" · ")
            }
            append(chapterTitle)
        }
        if (title.isBlank()) return

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = headerTextColor
            textSize = layoutConfig.headerFontPx()
        }
        canvas.save()
        canvas.translate(layoutConfig.columnLeft(), 0f)
        val layout = StaticLayout.Builder
            .obtain(title, 0, title.length, paint, layoutConfig.columnWidth().toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawCardSlot(canvas: Canvas, slot: SlotFrame) {
        drawCard(canvas)
        drawSlotContent(canvas, slot, slot.paragraphIndex)
    }

    private fun drawSlot(canvas: Canvas, slot: SlotFrame) {
        drawSlotContent(canvas, slot, slot.paragraphIndex)
    }

    private fun drawCard(canvas: Canvas) {
        val card = layoutConfig.cardRect()
        val fillAlpha = (Color.alpha(videoStyle.cardFillArgb)
            * videoStyle.currentCardAlpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (videoStyle.cardFillArgb and 0x00FFFFFF) or (fillAlpha shl 24)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(card, layoutConfig.cardCornerRadius, layoutConfig.cardCornerRadius, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = videoStyle.cardStrokeArgb
            style = Paint.Style.STROKE
            strokeWidth = layoutConfig.cardStrokeWidth
        }
        canvas.drawRoundRect(card, layoutConfig.cardCornerRadius, layoutConfig.cardCornerRadius, stroke)
    }

    private fun drawSlotContent(canvas: Canvas, slot: SlotFrame, index: Int) {
        val entry = layoutCache.layoutFor(index, timeline.paragraphs[index].displayText)
        val layoutW = entry.layout.width.toFloat()
        canvas.save()
        // Strict corridor-window clip BEFORE any transformation: no glyph can
        // leave its own slot/card region.
        canvas.clipRect(slot.window)
        canvas.save()
        // Local origin: the text-area center is the scale pivot. The scroll
        // offset is part of the vertical position, so highlight and text move
        // together.
        canvas.translate(layoutConfig.textX0() + layoutW / 2f, slot.rect.top)
        canvas.scale(slot.scale, slot.scale)
        canvas.translate(-layoutW / 2f, 0f)

        layoutCache.paint.alpha = (0xFF * slot.alpha).toInt()

        slot.highlightWordRange?.let { r ->
            if (!r.isEmpty()) {
                val rects = HighlightSpan.wordRects(
                    entry.layout, r.first, r.last + 1, layoutConfig.highlightPadding,
                )
                val hpaint = HighlightSpan.paint(layoutConfig.highlightColorArgb)
                hpaint.alpha = (0xFF * layoutConfig.highlightAlpha.coerceIn(0f, 1f) * slot.alpha).toInt()
                for (rc in rects) {
                    canvas.drawRoundRect(rc, layoutConfig.highlightRadius, layoutConfig.highlightRadius, hpaint)
                }
            }
        }
        entry.layout.draw(canvas)

        canvas.restore()
        canvas.restore()
        layoutCache.resetAlpha()
    }
}