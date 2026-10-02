package my.noveldokusha.text_to_speech

import java.util.concurrent.atomic.AtomicInteger

/**
 * Процесс-широкий арбитр между интерактивным чтением и фоновым экспортом.
 *
 * У TTS-движка Android один поток синтеза на сервис: и `speak`, и
 * `synthesizeToFile` обрабатываются строго последовательно (см.
 * `TextToSpeechService.SynthHandler`). Живое чтение держит глубокую
 * очередь абзацев, поэтому запрос экспорта встаёт за ней и выглядит
 * «зависшим». Пока идёт пакетный синтез, читалка уменьшает свой запас,
 * чтобы движок периодически освобождался под экспорт, а звук чтения
 * при этом не прерывался.
 */
object TtsSynthesisCoordinator {

    private val batchCount = AtomicInteger(0)

    /** true, пока хотя бы один экспорт синтезирует аудио. */
    val isBatchActive: Boolean get() = batchCount.get() > 0

    fun beginBatch() {
        batchCount.incrementAndGet()
    }

    fun endBatch() {
        while (true) {
            val current = batchCount.get()
            if (current <= 0) return
            if (batchCount.compareAndSet(current, current - 1)) return
        }
    }
}
