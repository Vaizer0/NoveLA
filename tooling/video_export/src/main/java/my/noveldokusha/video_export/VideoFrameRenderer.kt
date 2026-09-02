package my.noveldokusha.video_export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.ColorInt
import my.noveldokusha.reader_visuals.BackgroundLayer
import my.noveldokusha.reader_visuals.BackgroundType
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
 * Цвета карточки ([cardFillArgb]/[cardStrokeArgb]) резолвятся один раз при
 * создании рендера из реальной темы ([resolveThemeCardColors]).
 */
class VideoFrameRenderer(
    private val snapshot: ReaderVisualSnapshot,
    private val timeline: VideoExportTimeline,
    private val typeface: Typeface,
    @ColorInt private val resolvedTextColorArgb: Int,
    @ColorInt private val cardFillArgb: Int,
    @ColorInt private val cardStrokeArgb: Int,
    private val novelTitle: String = "",
    private val chapterTitle: String = "",
    @ColorInt private val headerTextColorArgb: Int? = null,
    private val backgroundImageDecoder: (File) -> Bitmap? = { null },
    private val backgroundFileResolver: (String) -> File? = { null },
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

    /** Геометрия одного слота конвейера на конкретном кадре. */
    data class SlotFrame(
        val paragraphIndex: Int?,
        val rect: RectF,
        val clip: RectF,
        val scale: Float,
        val alpha: Float,
        /** Диапазон отображаемого текста текущего слова (только current). */
        val highlightWordRange: IntRange? = null,
    )

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
        private const val HIGHLIGHT_PAD_Y = 3f
        private const val HIGHLIGHT_RADIUS = 6f

        /** Крупный шрифт титула во вступлении (после озвучки — обычная шапка). */
        const val TITLE_INTRO_FONT_PX = 64f
        const val TITLE_INTRO_MAX_LINES = 2
        const val TITLE_INTRO_CAPTION_FONT_PX = 30f

        /**
         * Разрешает цвета карточки из реальной темы приложения: заливка —
         * ?attr/colorAccentTransparent (coreui), обводка — ?attr/colorControlHighlight
         * (framework). Вызывается один раз на экспорт; при неудаче — блюпринт.
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
         */
        fun resolveTextColor(
            snapshot: ReaderVisualSnapshot,
            defaultBackgroundArgb: Int = DEFAULT_BACKGROUND_ARGB,
        ): Int {
            snapshot.textColorArgb?.let { return it }
            val bgAvg = when (snapshot.backgroundType) {
                BackgroundType.PRESET -> ReaderVisualSnapshot.averageArgb(
                    snapshot.presetColorsArgb.ifEmpty { listOf(defaultBackgroundArgb) }
                )
                else -> defaultBackgroundArgb
            }
            return ReaderVisualSnapshot.autoTextColorForLuminance(bgAvg)
        }

        private const val DEFAULT_BACKGROUND_ARGB = 0xFF15181D.toInt()

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

    private val layoutCache = ParagraphLayoutCache(effectiveTypeface, resolvedTextColorArgb, layoutConfig)
    private val backgroundLayer: BackgroundLayer = snapshot.backgroundLayer(backgroundFileResolver)

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

    private val headerTextColor: Int = headerTextColorArgb
        ?: ((resolvedTextColorArgb and 0x00FFFFFF) or ((0xFF * HEADER_ALPHA).toInt() shl 24))

    private fun autofit(index: Int): Float =
        layoutCache.layoutFor(index, timeline.paragraphs[index].displayText).autofitScale

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
        val cardContentRect = layoutConfig.cardContentRect()

        fun currentOnly(): FramePlan = FramePlan(
            current = SlotFrame(
                paragraphIndex = i,
                rect = cardContentRect,
                clip = cardContentRect,
                scale = autofit(i),
                alpha = 1f,
                highlightWordRange = highlightRange(i, sample),
            ),
        )

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

        val prevRect = layoutConfig.prevSlotRect()
        val cardRect = layoutConfig.cardRect()
        val nextRect = layoutConfig.nextSlotRect()
        val preRoll = layoutConfig.nextPreRollRect()

        fun steady(): FramePlan = FramePlan(
            prev = prevIndex(i)?.let {
                SlotFrame(it, prevRect, prevRect, layoutConfig.previewScale, layoutConfig.previewAlpha)
            },
            current = SlotFrame(
                paragraphIndex = i,
                rect = cardContentRect,
                clip = cardContentRect,
                scale = autofit(i),
                alpha = 1f,
                highlightWordRange = highlightRange(i, sample),
            ),
            next = nextIndex(i)?.let {
                SlotFrame(it, nextRect, nextRect, layoutConfig.previewScale, layoutConfig.previewAlpha)
            },
        )

        // Первый абзац не «всплывает» с места next — конвейер работает между
        // существующими абзацами, начиная со второго.
        if (i == 0 || rawT <= 0f) return steady()

        return FramePlan(
            fadingOut = if (i >= 2) {
                SlotFrame(
                    paragraphIndex = i - 2,
                    rect = prevRect,
                    clip = prevRect,
                    scale = layoutConfig.previewScale,
                    alpha = layoutConfig.previewAlpha * (1f - t),
                )
            } else {
                null
            },
            prev = prevIndex(i)?.let {
                SlotFrame(
                    paragraphIndex = it,
                    rect = VideoLayoutSpec.lerpRect(cardContentRect, prevRect, t),
                    clip = layoutConfig.band(prevRect, cardContentRect),
                    scale = VideoLayoutSpec.lerp(autofit(it), layoutConfig.previewScale, t),
                    alpha = VideoLayoutSpec.lerp(1f, layoutConfig.previewAlpha, t),
                )
            },
            current = SlotFrame(
                paragraphIndex = i,
                rect = VideoLayoutSpec.lerpRect(nextRect, cardContentRect, t),
                clip = layoutConfig.band(cardRect, nextRect),
                scale = VideoLayoutSpec.lerp(layoutConfig.previewScale, autofit(i), t),
                alpha = VideoLayoutSpec.lerp(layoutConfig.previewAlpha, 1f, t),
                highlightWordRange = highlightRange(i, sample),
            ),
            next = nextIndex(i)?.let {
                SlotFrame(
                    paragraphIndex = it,
                    rect = VideoLayoutSpec.lerpRect(preRoll, nextRect, t),
                    clip = layoutConfig.band(nextRect, preRoll),
                    scale = layoutConfig.previewScale,
                    alpha = VideoLayoutSpec.lerp(0f, layoutConfig.previewAlpha, t),
                )
            },
        )
    }

    /** Рисует кадр [sample] на [canvas] (должен быть 1920×1080). */
    fun renderFrame(canvas: Canvas, sample: Long) {
        drawBackground(canvas)
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
            val rects = HighlightSpan.wordRects(layout, range.first, range.last + 1, HIGHLIGHT_PAD_Y)
            val hpaint = HighlightSpan.paint(layoutConfig.highlightColorArgb)
            hpaint.alpha = (0xFF * layoutConfig.highlightAlpha.coerceIn(0f, 1f)).toInt()
            for (rc in rects) {
                canvas.drawRoundRect(rc, HIGHLIGHT_RADIUS, HIGHLIGHT_RADIUS, hpaint)
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
        val i = slot.paragraphIndex ?: return
        drawCard(canvas)
        drawSlotContent(canvas, slot, i)
    }

    private fun drawSlot(canvas: Canvas, slot: SlotFrame) {
        val i = slot.paragraphIndex ?: return
        drawSlotContent(canvas, slot, i)
    }

    private fun drawCard(canvas: Canvas) {
        val card = layoutConfig.cardRect()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardFillArgb
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(card, layoutConfig.cardCornerRadius, layoutConfig.cardCornerRadius, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardStrokeArgb
            style = Paint.Style.STROKE
            strokeWidth = layoutConfig.cardStrokeWidth
        }
        canvas.drawRoundRect(card, layoutConfig.cardCornerRadius, layoutConfig.cardCornerRadius, stroke)
    }

    private fun drawSlotContent(canvas: Canvas, slot: SlotFrame, index: Int) {
        val entry = layoutCache.layoutFor(index, timeline.paragraphs[index].displayText)
        canvas.save()
        canvas.clipRect(slot.clip)
        canvas.save()
        canvas.translate(0f, slot.rect.top)
        // Локальная область layout (x0..cardTextWidth) смещается в левый край
        // контента карточки, после чего центрированно масштабируется от контента.
        canvas.translate(layoutConfig.textX0(), 0f)
        canvas.scale(slot.scale, slot.scale, layoutConfig.contentCenterX, 0f)

        layoutCache.paint.alpha = (0xFF * slot.alpha).toInt()

        slot.highlightWordRange?.let { r ->
            if (!r.isEmpty()) {
                val rects = HighlightSpan.wordRects(entry.layout, r.first, r.last + 1, HIGHLIGHT_PAD_Y)
                val hpaint = HighlightSpan.paint(layoutConfig.highlightColorArgb)
                hpaint.alpha = (0xFF * layoutConfig.highlightAlpha.coerceIn(0f, 1f) * slot.alpha).toInt()
                for (rc in rects) {
                    canvas.drawRoundRect(rc, HIGHLIGHT_RADIUS, HIGHLIGHT_RADIUS, hpaint)
                }
            }
        }
        entry.layout.draw(canvas)

        canvas.restore()
        canvas.restore()
        layoutCache.resetAlpha()
    }
}