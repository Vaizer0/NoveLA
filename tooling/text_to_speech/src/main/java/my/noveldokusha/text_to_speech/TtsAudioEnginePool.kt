package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns export-only TTS clients. Reader/live TTS never enters this pool.
 * Up to five chapter exports can synthesize concurrently, each with its own client.
 * Instances are kept alive and reused while the app process lives; WorkManager recreates
 * them automatically after a process restart when a persisted export resumes.
 *
 * IMPORTANT: this pool never calls stop()/shutdown() on a leased client during normal export
 * lifecycle. Reader TTS has its own AppTtsEngine client and is not shared with these leases.
 */
object TtsAudioEnginePool {
    const val MAX_INSTANCES = 5

    private class Slot(var tts: TextToSpeech? = null, var enginePackage: String = "")

    private val availableSlots = Channel<Int>(MAX_INSTANCES).also { channel ->
        repeat(MAX_INSTANCES) { channel.trySend(it) }
    }
    private val slots = Array(MAX_INSTANCES) { Slot() }

    /** Number of export jobs currently holding a TTS lease. */
    @Volatile
    var activeExportCount: Int = 0
        private set

    private val activeExportCounter = AtomicInteger(0)

    fun hasActiveExports(): Boolean = activeExportCounter.get() > 0

    suspend fun acquire(context: Context, enginePackage: String): Lease {
        val index = availableSlots.receive()
        try {
            val slot = slots[index]
            val requestedPackage = enginePackage.trim()
            if (slot.tts == null || slot.enginePackage != requestedPackage) {
                // The slot is unleased at this point, so retire the old export-only client
                // after the replacement is ready. Reader TTS is never stored in this pool.
                val previous = slot.tts
                val created = createTts(context.applicationContext, requestedPackage)
                slot.tts = created
                slot.enginePackage = requestedPackage
                previous?.let { runCatching { it.shutdown() } }
            }
            activeExportCount = activeExportCounter.incrementAndGet()
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
            activeExportCount = activeExportCounter.decrementAndGet().coerceAtLeast(0)
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
