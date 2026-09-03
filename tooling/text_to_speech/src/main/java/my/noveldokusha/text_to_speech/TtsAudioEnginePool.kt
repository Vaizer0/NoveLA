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
                runCatching { slot.tts?.stop() }
                runCatching { slot.tts?.shutdown() }
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
            runCatching { tts.stop() }
            availableSlots.trySend(index)
        }
    }

    private suspend fun createTts(context: Context, enginePackage: String): TextToSpeech =
        suspendCancellableCoroutine { continuation ->
            lateinit var created: TextToSpeech
            created = TextToSpeech(
                context,
                { status ->
                    if (!continuation.isActive) return@TextToSpeech
                    if (status == TextToSpeech.SUCCESS) {
                        continuation.resume(created)
                    } else {
                        continuation.resumeWithException(
                            TtsExportException(
                                "TTS engine '${enginePackage.ifBlank { "default" }}' init failed: status=$status"
                            )
                        )
                    }
                },
                enginePackage.ifBlank { null },
            )
            continuation.invokeOnCancellation {
                runCatching { created.shutdown() }
            }
        }
}
