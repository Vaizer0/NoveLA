package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Регрессия на баг «прыжок с середины абзаца»: стабильный размер файла во
 * время паузы записи TTS не должен считаться завершением синтеза.
 */
class SynthesisCompletionGateTest {

    private fun gate(rounds: Int = 2, min: Long = 44L) =
        SynthesisCompletionGate(stableRoundsRequired = rounds, minAudioBytes = min)

    private fun SynthesisCompletionGate.eval(
        size: Long,
        done: Boolean = false,
        failed: Boolean = false,
        speaking: Boolean = false,
        complete: Boolean = true,
    ): SynthesisCompletion = evaluate(done, failed, size, speaking) { complete }

    @Test
    fun sizePlateauDuringActiveSynthesisIsNotCompletion() {
        val gate = gate()
        // Файл перестал расти, но движок ещё «говорит»: это пауза записи, а не
        // завершение. Сколько бы раз ни повторялся размер — ждать.
        repeat(10) {
            assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 1000, speaking = true))
        }
    }

    @Test
    fun incompleteContainerIsNotCompletion() {
        val gate = gate()
        repeat(10) {
            assertEquals(
                SynthesisCompletion.WAIT,
                gate.eval(size = 1000, speaking = false, complete = false),
            )
        }
    }

    @Test
    fun stableFileWithoutSpeakingAndCompleteContainerCompletes() {
        val gate = gate(rounds = 2)
        assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 1000))
        assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 1000))
        assertEquals(SynthesisCompletion.ACCEPT, gate.eval(size = 1000))
    }

    @Test
    fun growthResetsStabilityCounter() {
        val gate = gate(rounds = 2)
        assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 1000))
        assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 2000))
        assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 2000))
        assertEquals(SynthesisCompletion.ACCEPT, gate.eval(size = 2000))
    }

    @Test
    fun onDoneAcceptsImmediately() {
        val gate = gate()
        assertEquals(SynthesisCompletion.ACCEPT, gate.eval(size = 0, done = true))
    }

    @Test
    fun onErrorFails() {
        val gate = gate()
        assertEquals(SynthesisCompletion.FAIL, gate.eval(size = 1000, failed = true))
    }

    @Test
    fun headerOnlyFileIsNotEnough() {
        val gate = gate()
        repeat(10) {
            assertEquals(SynthesisCompletion.WAIT, gate.eval(size = 44))
        }
    }

    @Test
    fun containerCheckIsLazyAndRunsOnlyWhenSizeIsStable() {
        var calls = 0
        val gate = gate(rounds = 2)
        gate.evaluate(false, false, 1000, false) { calls++; true }
        gate.evaluate(false, false, 2000, false) { calls++; true }
        gate.evaluate(false, false, 3000, false) { calls++; true }
        assertEquals(0, calls)
        gate.evaluate(false, false, 3000, false) { calls++; true }
        gate.evaluate(false, false, 3000, false) { calls++; true }
        assertEquals(1, calls)
    }
}
