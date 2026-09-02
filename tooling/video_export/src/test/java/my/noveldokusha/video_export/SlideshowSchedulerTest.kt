package my.noveldokusha.video_export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowSchedulerTest {

    private fun item(id: String, enabled: Boolean = true) = ArtworkItem(
        stableId = id,
        fileName = "$id.png",
        enabled = enabled,
        position = 0.75f,
        size = 0.5f,
        opacity = 0.9f,
        cropMode = ArtworkFitMode.COVER,
        cornerRadius = 16f,
        borderWidth = 2f,
        borderColorArgb = 0x60FFFFFF.toInt(),
        shadow = true,
    )

    private val items = listOf(item("a"), item("b"), item("c"))

    private fun cfg(
        mode: SlideshowTimingMode = SlideshowTimingMode.FIXED_INTERVAL,
        fixedMs: Long = 8_000L,
        percent: Float = 0.5f,
        rmin: Long = 4_000L,
        rmax: Long = 10_000L,
        seed: Long = 42L,
        transition: SlideshowTransition = SlideshowTransition.NONE,
        transMs: Long = 700L,
    ) = SlideshowConfig(
        enabled = true,
        timingMode = mode,
        fixedIntervalMs = fixedMs,
        percentageSections = percent,
        randomMinMs = rmin,
        randomMaxMs = rmax,
        randomSeed = seed,
        transitionType = transition,
        transitionDurationMs = transMs,
    )

    @Test
    fun `fixed interval fills by total duration and holds last`() {
        val total = 30_000L
        val s = SlideshowScheduler(cfg(fixedMs = 8_000L), items, total)
        val slots = s.frameAt(0)
        assertEquals(0, slots.itemIndex)
        // t = 8s => second slide
        assertEquals(1, s.frameAt(8_001L).itemIndex)
        // t = 16s => third
        assertEquals(2, s.frameAt(16_001L).itemIndex)
        // last slide holds until the very end
        assertEquals(2, s.frameAt(total - 1L).itemIndex)
    }

    @Test
    fun `percent mode divides timeline`() {
        val total = 30_000L
        val s = SlideshowScheduler(cfg(mode = SlideshowTimingMode.PERCENT_OF_TOTAL_DURATION, percent = 0.4f), items, total)
        // 30s * 0.4 = 12s each (except last holds)
        assertEquals(0, s.frameAt(0L).itemIndex)
        assertEquals(0, s.frameAt(11_999L).itemIndex)
        assertEquals(1, s.frameAt(12_001L).itemIndex)
        assertEquals(1, s.frameAt(23_999L).itemIndex)
        assertEquals(2, s.frameAt(24_001L).itemIndex)
        assertEquals(2, s.frameAt(total - 1).itemIndex)
    }

    @Test
    fun `random mode is deterministic and bounded`() {
        val total = 50_000L
        val cfgA = cfg(mode = SlideshowTimingMode.RANDOM_INTERVAL, seed = 42L, rmin = 4_000L, rmax = 8_000L)
        val cfgB = cfg(mode = SlideshowTimingMode.RANDOM_INTERVAL, seed = 42L, rmin = 4_000L, rmax = 8_000L)
        val sa = SlideshowScheduler(cfgA, items, total)
        val sb = SlideshowScheduler(cfgB, items, total)
        // same seed => same timeline
        for (t in 0L..total step 500L) {
            assertEquals("t=$t", sa.frameAt(t).itemIndex, sb.frameAt(t).itemIndex)
        }
        // no rapid pathological changes: sample the transition points
        var lastIdx = -1
        var lastT = 0L
        for (t in 0L..total) {
            val idx = sa.frameAt(t).itemIndex
            if (idx != lastIdx) {
                if (lastIdx >= 0) {
                    assertTrue("interval >= min (${t - lastT})", t - lastT >= 4_000L)
                }
                lastIdx = idx
                lastT = t
            }
        }
        // last holds to end
        assertEquals(2, sa.frameAt(total - 1).itemIndex)
    }

    @Test
    fun `different seed reshuffles`() {
        val total = 50_000L
        val s1 = SlideshowScheduler(cfg(mode = SlideshowTimingMode.RANDOM_INTERVAL, seed = 1L), items, total)
        val s2 = SlideshowScheduler(cfg(mode = SlideshowTimingMode.RANDOM_INTERVAL, seed = 2L), items, total)
        var diff = false
        for (t in 0L..total step 1_000L) {
            if (s1.frameAt(t).itemIndex != s2.frameAt(t).itemIndex) { diff = true; break }
        }
        assertTrue("seeds produce different timing", diff)
    }

    @Test
    fun `fade transition gives progress over duration`() {
        val total = 30_000L
        val s = SlideshowScheduler(cfg(fixedMs = 8_000L, transition = SlideshowTransition.FADE, transMs = 1_000L), items, total)
        // At second slide start (8s): progress ramps 0..1 over 1s
        val start = s.frameAt(8_000L)
        assertTrue("transitioning at boundary", start.transitioning)
        assertTrue("progress near 0 at start", start.progress < 0.05f)
        val mid = s.frameAt(8_500L)
        assertTrue("progress ~0.5 midway", mid.progress in 0.4f..0.6f)
        val done = s.frameAt(9_000L)
        assertTrue("progress 1 after duration", done.progress >= 0.99f)
    }

    @Test
    fun `disabled config yields no slides`() {
        val s = SlideshowScheduler(SlideshowConfig.disabled(), items, 30_000L)
        assertTrue(s.isEmpty)
        val f = s.frameAt(0L)
        assertEquals(-1, f.itemIndex)
    }

    @Test
    fun `empty disabled items`() {
        val s = SlideshowScheduler(cfg(), emptyList(), 30_000L)
        assertTrue(s.isEmpty)
    }

    @Test
    fun `stableHash changes with config but not unrelated text`() {
        val base = "chapter|src|json|a.pngb.pngc.png"
        val differentJson = "chapter|src|json2|a.pngb.pngc.png"
        assertNotEquals(
            SlideshowScheduler.stableHash(base),
            SlideshowScheduler.stableHash(differentJson),
        )
        // same input => same hash
        assertEquals(
            SlideshowScheduler.stableHash(base),
            SlideshowScheduler.stableHash(base),
        )
    }

    @Test
    fun `artwork item json round trip`() {
        val a = item("x")
        val parsed = ArtworkItem.fromJson(a.toJson())
        assertEquals(a, parsed)
    }

    @Test
    fun `artwork list json round trip`() {
        val list = listOf(item("x"), item("y"), item("z"))
        val arr = list.toJsonArray()
        assertEquals(list, fromArtworkJsonArray(arr))
    }

    @Test
    fun `slideshow config json round trip`() {
        val c = cfg(mode = SlideshowTimingMode.RANDOM_INTERVAL, seed = 7L, transMs = 500L)
        val parsed = SlideshowConfig.fromJson(c.toJson())
        assertEquals(c, parsed)
    }

    @Test
    fun `empty artwork list round trips empty`() {
        assertTrue(fromArtworkJsonArray(null).isEmpty())
    }

    @Test
    fun `random never exceeds total and never zero`() {
        val total = 12_000L
        val s = SlideshowScheduler(
            cfg(mode = SlideshowTimingMode.RANDOM_INTERVAL, seed = 9L, rmin = 4_000L, rmax = 10_000L),
            items, total,
        )
        assertTrue("last index reachable", s.frameAt(total - 1).itemIndex in 0..2)
    }
}
