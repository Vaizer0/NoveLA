package my.noveldokusha.reader_visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderVisualSnapshotTest {

    private val paper = ReaderBackgroundPresets.first { it.id == "paper" }
    private val midnight = ReaderBackgroundPresets.first { it.id == "midnight" }

    @Test
    fun preset_textColor_parsesToArgb() {
        assertEquals("FF2C2C34", paper.textColor)
        assertEquals(0xFF2C2C34.toInt(), paper.textColorArgb)
    }

    @Test
    fun parseArgb_invalidHex_returnsNull() {
        assertNull(parseArgb("not-a-color"))
    }

    @Test
    fun parseArgb_validHex_returnsInt() {
        assertEquals(0xFF000000.toInt(), parseArgb("FF000000"))
    }

    @Test
    fun backgroundLayer_autoValue_isNone() {
        assertTrue(backgroundLayer("") is BackgroundLayer.None)
    }

    @Test
    fun backgroundLayer_presetId_isPreset() {
        val layer = backgroundLayer("paper")
        assertTrue(layer is BackgroundLayer.Preset)
        assertEquals(3, (layer as BackgroundLayer.Preset).preset.colors.size)
    }

    @Test
    fun backgroundLayer_unknownValue_noFile_isNone() {
        assertTrue(backgroundLayer("nope", fileResolver = { null }) is BackgroundLayer.None)
    }

    @Test
    fun backgroundLayer_filePref_resolvesThroughResolver() {
        val resolved = backgroundLayer(
            "background_file:wallpaper.png",
            fileResolver = { v ->
                assertTrue(v.startsWith("background_file:"))
                java.io.File(v)
            }
        )
        assertTrue(resolved is BackgroundLayer.Image)
    }

    @Test
    fun computeBaseFontPx_readerDefault_mapsToVideoPx() {
        assertEquals(62f, ReaderVisualSnapshot.computeBaseFontPx(14f), 1e-4f)
    }

    @Test
    fun averageArgb_lightPreset_isLight() {
        val avg = ReaderVisualSnapshot.averageArgb(paper.colors)
        assertTrue("expected light bg, got $avg", luminance(avg) > 0.7f)
    }

    @Test
    fun averageArgb_darkPreset_isDark() {
        val avg = ReaderVisualSnapshot.averageArgb(midnight.colors)
        assertTrue("expected dark bg, got $avg", luminance(avg) < 0.2f)
    }

    @Test
    fun autoTextColor_lightBackground_darkText() {
        assertEquals(0xFF000000.toInt(), ReaderVisualSnapshot.autoTextColorForLuminance(paper.textColorArgb))
    }

    @Test
    fun autoTextColor_darkBackground_lightText() {
        assertEquals(0xFFFFFFFF.toInt(), ReaderVisualSnapshot.autoTextColorForLuminance(ReaderVisualSnapshot.averageArgb(midnight.colors)))
    }

    @Test
    fun snapshot_jsonRoundTrip_preservesFields() {
        val snapshot = ReaderVisualSnapshot(
            fontFamily = "serif",
            fontSizeSp = 14f,
            lineHeight = 1.35f,
            letterSpacing = 0f,
            paragraphSpacing = 8f,
            textColorArgb = 0xFF2C2C34.toInt(),
            backgroundType = BackgroundType.PRESET,
            presetId = paper.id,
            presetColorsArgb = paper.colors,
            backgroundFileName = "",
            ttsHighlightColorArgb = 0xFFFF6D00.toInt(),
            derivedBaseFontPx = 62f,
        )
        val restored = ReaderVisualSnapshot.fromJson(snapshot.toJson())
        assertEquals(snapshot, restored)
    }

    @Test
    fun snapshot_nullTextColor_roundTripsAsAuto() {
        val snapshot = ReaderVisualSnapshot(
            fontFamily = "serif",
            fontSizeSp = 16f,
            lineHeight = 1.4f,
            letterSpacing = 0.05f,
            paragraphSpacing = 12f,
            textColorArgb = null,
            backgroundType = BackgroundType.NONE,
            presetId = "",
            presetColorsArgb = emptyList(),
            backgroundFileName = "",
            ttsHighlightColorArgb = 0xFFFF6D00.toInt(),
            derivedBaseFontPx = ReaderVisualSnapshot.computeBaseFontPx(16f),
        )
        val restored = ReaderVisualSnapshot.fromJson(snapshot.toJson())
        assertEquals(snapshot, restored)
    }

    private fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }
}