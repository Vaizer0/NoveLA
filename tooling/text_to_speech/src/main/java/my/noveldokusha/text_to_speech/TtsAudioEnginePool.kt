package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns export-only TTS clients. Reader TTS never enters this pool.
 * Up to five chapter exports can synthesize concurrently, each with its own client.
 * Instances are kept alive and reused while the app process lives; WorkManager recreates
 * them automatically after a process restart when a persisted export resumes.
 *
 * IMPORTANT: pooled clients are never stopped or shut down during normal export lifecycle.
 * Android scopes TTS queue control to the calling app, so stop/shutdown from an export path
 * can interfere with the reader's live TTS even though the Java client objects differ.
 */
object TtsAudioEnginePool {
    const val MAX_INSTANCES = 5

    private class Slot(var tts: TextToSpeech? = null, var enginePackage: String = "")

    private val availableSlots = Channel<Int>(MAX_INSTANCES).also { channel ->
        repeat(MAX_INSTANCES) { channel.trySend(it) }
    }
    private val slots = Array(MAX_INSTANCES) { Slot() }

    suspend fun acquire(context: Context, enginePackage: String): Lease {
        val index = availableSlots.receive()
        try {
            val slot = slots[index]
            val requestedPackage = enginePackage.trim()
            if (slot.tts == null || slot.enginePackage != requestedPackage) {
                // Do not stop/shutdown the previous export client: those lifecycle calls can
                // affect the application's other TTS requests. The old client is simply
                // retired by replacing the slot reference after the new client is ready.
                slot.tts = createTts(context.applicationContext, requestedPackage)
                slot.enginePackage = requestedPackage
            }
            return Lease(index, slot.tts!!)
        } catch (t: Throwable) {
            availableSlots.send(index)
            throw t
        }
    }

    class Lease internal constructor(
        private val index: Int,
        val tts: TextToSpeech,
    ) : AutoCloseable {
        private var released = false

        override fun close() {
            if (released) return
            released = true
            // Deliberately do not call tts.stop()/shutdown(). The exporter waits for the
            // current synthesis before releasing this lease, and the pool reuses the client.
            availableSlots.trySend(index)
        }
    }

    private suspend fun createTts(context: Context, enginePackage: String): TextToSpeech =
        suspendCancellableCoroutine { continuation ->
            lateinit var created: TextToSpeech
            created = TextToSpeech(
                context,
                { status ->
                    if (continuation.isActive) {
                        if (status == TextToSpeech.SUCCESS) {
                            continuation.resume(created)
                        } else {
                            continuation.resumeWithException(
                                TtsExportException(
                                    "TTS engine '${enginePackage.ifBlank { "default" }}' init failed: status=$status"
                                )
                            )
                        }
                    }
                },
                enginePackage.ifBlank { null },
            )
            continuation.invokeOnCancellation {
                runCatching { created.shutdown() }
            }
        }
}
