package my.noveldokusha.text_to_speech

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Confirms the JVM test classpath provides the JSON parser used by video request persistence. */
class TtsVideoSerializationDependencyTest {
    @Test fun jsonParserAvailableOnJvm() {
        assertEquals("ok", JSONObject("{\"status\":\"ok\"}").getString("status"))
    }
}
