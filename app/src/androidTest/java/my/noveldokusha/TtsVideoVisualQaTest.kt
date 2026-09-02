package my.noveldokusha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.noveldokusha.text_to_speech.ArtworkMode
import my.noveldokusha.text_to_speech.BackgroundMode
import my.noveldokusha.text_to_speech.LongParagraphMode
import my.noveldokusha.text_to_speech.ParagraphDisplayMode
import my.noveldokusha.text_to_speech.TimelineTimingMode
import my.noveldokusha.text_to_speech.TtsVideoCompositionRenderer
import my.noveldokusha.text_to_speech.TtsVideoTimeline
import my.noveldokusha.text_to_speech.TtsVideoVisualSettings
import my.noveldokusha.text_to_speech.TtsVideoVisualSnapshot
import my.noveldokusha.text_to_speech.VideoParagraph
import my.noveldokusha.text_to_speech.VideoSpokenRange
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TtsVideoVisualQaTest {
    @Test
    fun renderRepresentativeFramesToPng() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val renderer = TtsVideoCompositionRenderer(context)
        val outputDir = File(context.filesDir, "tts-video-qa").apply {
            deleteRecursively()
            mkdirs()
        }

        val paragraphs = listOf(
            VideoParagraph(
                id = "0:0",
                displayText = "The ancient city slept beneath a silver moon. The wind carried whispers through the empty streets.",
                preparedText = "The ancient city slept beneath a silver moon. The wind carried whispers through the empty streets.",
                startUs = 0,
                endUs = 4_000_000,
                blockIndex = 0,
                spokenRanges = listOf(
                    VideoSpokenRange(0, 1_000_000, 0, 8, 0, 8, TimelineTimingMode.EXACT),
                    VideoSpokenRange(1_000_000, 2_000_000, 9, 13, 9, 13, TimelineTimingMode.EXACT),
                    VideoSpokenRange(2_000_000, 3_000_000, 14, 18, 14, 18, TimelineTimingMode.EXACT),
                    VideoSpokenRange(3_000_000, 4_000_000, 19, 24, 19, 24, TimelineTimingMode.EXACT),
                ),
            ),
            VideoParagraph(
                id = "1:0",
                displayText = "A very long paragraph demonstrates the AUTO_FIT behavior. " +
                    "The renderer must reduce only the current paragraph when needed, keep the text inside the safe region, and never clip the final lines.",
                preparedText = "A very long paragraph demonstrates the AUTO_FIT behavior. " +
                    "The renderer must reduce only the current paragraph when needed, keep the text inside the safe region, and never clip the final lines.",
                startUs = 4_000_000,
                endUs = 8_000_000,
                blockIndex = 1,
                spokenRanges = listOf(
                    VideoSpokenRange(4_000_000, 5_000_000, 0, 1, 0, 1, TimelineTimingMode.EXACT),
                    VideoSpokenRange(5_000_000, 6_000_000, 2, 6, 2, 6, TimelineTimingMode.EXACT),
                    VideoSpokenRange(6_000_000, 7_000_000, 7, 17, 7, 17, TimelineTimingMode.EXACT),
                    VideoSpokenRange(7_000_000, 8_000_000, 18, 28, 18, 28, TimelineTimingMode.EXACT),
                ),
            ),
        )
        val timeline = TtsVideoTimeline(paragraphs, 8_000_000, TimelineTimingMode.EXACT)

        val base = TtsVideoVisualSettings(
            paragraphMode = ParagraphDisplayMode.CURRENT_WITH_CONTEXT,
            longParagraphMode = LongParagraphMode.AUTO_FIT,
            backgroundMode = BackgroundMode.SOLID,
        )
        val fitFrames = longArrayOf(0, 1_500_000, 4_000_000, 6_500_000)
        fitFrames.forEachIndexed { index, timeUs ->
            render(renderer, timeline, base, TtsVideoVisualSnapshot(), timeUs, File(outputDir, "auto-fit-$index.png"))
        }

        val smooth = base.copy(
            longParagraphMode = LongParagraphMode.SMOOTH_SCROLL,
            paragraphMode = ParagraphDisplayMode.CURRENT_ONLY,
        )
        render(renderer, timeline, smooth, TtsVideoVisualSnapshot(), 6_500_000, File(outputDir, "smooth-scroll.png"))

        val artworkBitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        Canvas(artworkBitmap).apply {
            drawColor(android.graphics.Color.rgb(48, 32, 90))
            drawCircle(300f, 300f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(220, 180, 80)
            })
        }
        try {
            val artwork = base.copy(
                artworkMode = ArtworkMode.LEFT,
                artworkUris = listOf("memory://visual-qa/artwork"),
                artworkOverlay = false,
            )
            render(
                renderer,
                timeline,
                artwork,
                TtsVideoVisualSnapshot(artworkBitmaps = listOf(artworkBitmap)),
                1_500_000,
                File(outputDir, "artwork-safe-region.png"),
            )
        } finally {
            artworkBitmap.recycle()
        }

        check(outputDir.listFiles()?.count { it.extension == "png" } == 6)
    }

    private fun render(
        renderer: TtsVideoCompositionRenderer,
        timeline: TtsVideoTimeline,
        settings: TtsVideoVisualSettings,
        snapshot: TtsVideoVisualSnapshot,
        timeUs: Long,
        output: File,
    ) {
        val bitmap = Bitmap.createBitmap(settings.width, settings.height, Bitmap.Config.ARGB_8888)
        try {
            renderer.render(Canvas(bitmap), timeline, settings, snapshot, timeUs)
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(output.length() > 0) { "Empty visual QA output: ${output.name}" }
        } finally {
            bitmap.recycle()
        }
    }
}
