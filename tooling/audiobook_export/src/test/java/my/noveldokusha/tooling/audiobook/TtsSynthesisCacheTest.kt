package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключ кэша обязан быть стабильным для одинаковых входа и различаться при
 * любом изменении параметра синтеза — иначе WAV/MP4 переиспользуют чужое аудио.
 */
class TtsSynthesisCacheTest {

    private fun key(
        engine: String = "com.google.android.tts",
        voice: String = "ru-ru-x-rup-local",
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        text: String = "Привет, мир.",
    ) = TtsSynthesisCache.key(engine, voice, speed, pitch, text)

    @Test
    fun sameInputsProduceSameKey() {
        assertEquals(key(), key())
    }

    @Test
    fun keyIsStableSha256Hex() {
        val value = key()
        assertEquals(64, value.length)
        assertTrue(value.all { it in "0123456789abcdef" })
    }

    @Test
    fun textChangesKey() {
        assertNotEquals(key(text = "Привет, мир."), key(text = "Привет, мир!"))
    }

    @Test
    fun voiceChangesKey() {
        assertNotEquals(key(voice = "a"), key(voice = "b"))
    }

    @Test
    fun engineChangesKey() {
        assertNotEquals(key(engine = "engine.a"), key(engine = "engine.b"))
    }

    @Test
    fun speedChangesKey() {
        assertNotEquals(key(speed = 1.0f), key(speed = 1.5f))
    }

    @Test
    fun pitchChangesKey() {
        assertNotEquals(key(pitch = 1.0f), key(pitch = 0.5f))
    }

    @Test
    fun fieldBoundariesDoNotCollide() {
        // Разделитель полей не даёт склеить разные поля в один ключ.
        assertNotEquals(key(engine = "ab", voice = ""), key(engine = "a", voice = "b"))
    }
}
