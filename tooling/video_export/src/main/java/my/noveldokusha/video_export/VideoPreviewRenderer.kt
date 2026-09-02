package my.noveldokusha.video_export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import my.noveldokusha.reader_visuals.BackgroundType
import my.noveldokusha.reader_visuals.ReaderVisualSnapshot

/**
 * Внутриприложный превью (Phase I): рендерит ОДИН статичный кадр видео-экспорта
 * из [VideoStyleSettings] тем же [VideoFrameRenderer], что и реальный экспорт.
 *
 * Превью детерминировано и не зависит от текущего вида читалки: берётся
 * фиксированная «читалка-заглушка» (читаемый консервативный дефолт), а все
 * настройки видео резолвятся через [VideoStyleSettings.resolve], поэтому
 * картинка повторяет будущий кадр 1-в-1 по типографике/цветам/карточке/слайдам.
 *
 * Не используется в реальной кодировке — только для Settings → Video Appearance.
 */
object VideoPreviewRenderer {

    /** Высота/ширина совпадают с канвасом экспорта (1920×1080). */
    val WIDTH: Int = VideoLayoutSpec.WIDTH
    val HEIGHT: Int = VideoLayoutSpec.HEIGHT

    /** Размер превью на экране (уменьшенный для отображения). */
    const val PREVIEW_WIDTH_DP = 320f

    /**
     * Рендерит статичный кадр превью в начале первого абзаца [sampleText].
     */
    fun renderStylePreview(
        style: VideoStyleSettings,
        sampleText: String = DEFAULT_SAMPLE_TEXT,
    ): Bitmap {
        val reader = previewReaderSnapshot(style)
        val resolved = style.resolve(reader)
        val typeface = Typeface.create(style.fontFamily ?: "serif", Typeface.NORMAL)
        val timeline = syntheticTimeline(sampleText)
        val renderer = VideoFrameRenderer(
            snapshot = reader,
            timeline = timeline,
            typeface = typeface,
            novelTitle = "NoveLA",
            chapterTitle = "Video Preview",
            artworkImageDecoder = { null },
            videoStyle = resolved,
        )
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        // Немного внутрь первого абзаца, чтобы подсветка слова уже была видна.
        val sample = timeline.paragraphs.first().startSample + 250_000L
        renderer.renderFrame(Canvas(bitmap), sample)
        return bitmap
    }

    /** Читалка-заглушка для превью (консервативный детерминированный вид). */
    private fun previewReaderSnapshot(style: VideoStyleSettings): ReaderVisualSnapshot {
        val fontSizeSp = style.fontSizeSp ?: 18f
        return ReaderVisualSnapshot(
            fontFamily = style.fontFamily ?: "serif",
            fontSizeSp = fontSizeSp,
            lineHeight = style.lineHeight ?: 1.35f,
            letterSpacing = style.letterSpacing ?: 0f,
            paragraphSpacing = 8f,
            textColorArgb = style.textColorArgb,
            backgroundType = BackgroundType.NONE,
            presetId = "",
            presetColorsArgb = emptyList(),
            backgroundFileName = "",
            ttsHighlightColorArgb = style.highlightColorArgb ?: 0xFFFF6D00.toInt(),
            derivedBaseFontPx = ReaderVisualSnapshot.computeBaseFontPx(fontSizeSp),
        )
    }

    /** Один абзац с равномерно распределёнными словами по таймлайну. */
    private fun syntheticTimeline(text: String): VideoExportTimeline {
        val wordDurationUs = 220_000L
        val gapUs = 90_000L
        val places = wordRanges(text)
        val start = 100_000L
        val words = places.mapIndexed { k, r ->
            WordTiming(
                displayRange = r,
                samplePosition = start + k * wordDurationUs,
                isApproximate = false,
            )
        }
        val end = start + (places.size * wordDurationUs)
        val sampleRate = 44100
        val totalUs = end + gapUs
        val paragraph = ParagraphTiming(
            displayText = text,
            cleanedText = text,
            startSample = start,
            endSample = end,
            wordTimings = words,
        )
        return VideoExportTimeline(
            sampleRate = sampleRate,
            channelCount = 1,
            totalSamples = totalUs * sampleRate / 1_000_000L,
            paragraphs = listOf(paragraph),
        )
    }

    private fun wordRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val s = i
            while (i < text.length && !text[i].isWhitespace()) i++
            ranges.add(s until i)
        }
        return ranges
    }

    private const val DEFAULT_SAMPLE_TEXT =
        "The autumn wind carried the scent of rain through the empty streets."
}
