package my.noveldokusha

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.noveldokusha.text_to_speech.TimelineTimingMode
import my.noveldokusha.text_to_speech.TtsVideoCompositionRenderer
import my.noveldokusha.text_to_speech.TtsVideoMp4Encoder
import my.noveldokusha.text_to_speech.TtsVideoTimeline
import my.noveldokusha.text_to_speech.TtsVideoVisualSettings
import my.noveldokusha.text_to_speech.VideoParagraph
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsVideoMp4EncoderTest {
    @Test
    fun encodesAvcAacMp4WithMonotonicThirtyFpsVideo() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val wav = File(context.cacheDir, "encoder-smoke.wav")
        val mp4 = File(context.cacheDir, "encoder-smoke.mp4")
        wav.delete()
        mp4.delete()

        val sampleRate = 48_000
        val channels = 2
        val durationUs = 500_000L
        val pcmBytes = (durationUs * sampleRate * channels * 2L / 1_000_000L).toInt()
        my.noveldokusha.text_to_speech.WavWriter(wav).apply {
            open(sampleRate, channels)
            writePcm(ByteArray(pcmBytes) { index -> if (index % 17 == 0) 1 else 0 })
            finish()
        }

        val text = "Encoder smoke test for synchronized TTS video output."
        val timeline = TtsVideoTimeline(
            paragraphs = listOf(VideoParagraph("0:0", text, text, 0L, durationUs, 0)),
            durationUs = durationUs,
            timingMode = TimelineTimingMode.EXACT,
        )
        val settings = TtsVideoVisualSettings(width = 1920, height = 1080, fps = 30)

        try {
            kotlinx.coroutines.runBlocking {
                TtsVideoMp4Encoder().encode(
                    wav,
                    mp4,
                    timeline,
                    settings,
                    TtsVideoCompositionRenderer(context),
                    my.noveldokusha.text_to_speech.TtsVideoVisualSnapshot(),
                )
            }
            assertTrue(mp4.exists())
            assertTrue(mp4.length() > 0L)

            val extractor = MediaExtractor()
            extractor.setDataSource(mp4.absolutePath)
            try {
                var videoTrack = -1
                var audioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    when (format.getString(MediaFormat.KEY_MIME)) {
                        "video/avc" -> videoTrack = i
                        "audio/mp4a-latm" -> audioTrack = i
                    }
                }
                assertTrue(videoTrack >= 0)
                assertTrue(audioTrack >= 0)

                val videoFormat = extractor.getTrackFormat(videoTrack)
                assertEquals(1920, videoFormat.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(1080, videoFormat.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(30, videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE))

                val audioFormat = extractor.getTrackFormat(audioTrack)
                assertEquals(sampleRate, audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(channels, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))

                extractor.selectTrack(videoTrack)
                var count = 0
                var previousPts = Long.MIN_VALUE
                var firstPts = Long.MIN_VALUE
                var lastPts = Long.MIN_VALUE
                while (true) {
                    val sample = extractor.sampleTime
                    if (sample < 0) break
                    if (firstPts == Long.MIN_VALUE) firstPts = sample
                    if (previousPts != Long.MIN_VALUE) {
                        assertTrue("non-monotonic video PTS", sample > previousPts)
                    }
                    previousPts = sample
                    lastPts = sample
                    count++
                    if (!extractor.advance()) break
                }
                assertTrue("expected several video frames", count >= 10)
                assertEquals(0L, firstPts)
                assertTrue("video duration too short", lastPts >= 300_000L)
                assertTrue("video duration too long", lastPts <= 500_000L)
                assertFalse(mp4.length() == 0L)
            } finally {
                extractor.release()
            }
        } finally {
            wav.delete()
            mp4.delete()
        }
    }
}
