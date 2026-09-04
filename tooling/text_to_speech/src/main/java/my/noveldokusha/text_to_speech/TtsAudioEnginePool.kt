package my.noveldokusha.text_to_speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns export-only TTS clients. Reader TTS never enters this pool.
 * Up to five chapter exports can synthesize concurrently when the reader/live TTS is idle.
 * When live TTS is speaking through the same engine, one export slot is reserved for the
 * live client, so at most four export syntheses contend with it. This prevents the common
 * engine-service starvation seen when five background syntheses plus live playback are
 * active at the same time.
 *
 * Instances are kept alive and reused while the app process lives; WorkManager recreates
 * them automatically after a process restart when a persisted export resumes.
 *
 * IMPORTANT: pooled clients are never stopped or shut down during normal export lifecycle.
 */
object TtsAudioEnginePool {
    const val MAX_INSTANCES = 5
    private const val MAX_EXPORTS_WITH_LIVE_TTS = 4
    private const val SLOT_RETRY_DELAY_MS = 50L

    private class Slot(var tts: TextToSpeech? = null, var enginePackage: String = "")

    private val availableSlots = Channel<Int>(MAX_INSTANCES).also { channel ->
        repeat(MAX_INSTANCES) { channel.trySend(it) }
    }
    private val slots = Array(MAX_INSTANCES) { Slot() }
    private val activeExportCount = AtomicInteger(0)

    suspend fun acquire(context: Context, enginePackage: String): Lease {
        // Keep acquisition dynamic: if live TTS starts while exports are in progress,
        // existing syntheses finish normally, but the next chunk waits until one export
        // slot is free. This avoids creating a sixth active client against the same engine.
        while (true) {
            val limit = if (AppTtsEngine.getInstance(context).isSpeakingWithEngine(enginePackage)) {
                MAX_EXPORTS_WITH_LIVE_TTS
            } else {
                MAX_INSTANCES
            }
            val current = activeExportCount.get()
            if (current < limit && activeExportCount.compareAndSet(current, current + 1)) {
                break
            }
            delay(SLOT_RETRY_DELAY_MS)
        }

        val index = try {
            availableSlots.receive()
        } catch (t: Throwable) {
            activeExportCount.decrementAndGet()
            throw t
        }

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
            activeExportCount.decrementAndGet()
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
            activeExportCount.decrementAndGet()
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
