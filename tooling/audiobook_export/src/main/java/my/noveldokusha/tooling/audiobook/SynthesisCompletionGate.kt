package my.noveldokusha.tooling.audiobook

/** Решение о том, завершён ли синтез текущего TTS-фрагмента. */
internal enum class SynthesisCompletion { WAIT, ACCEPT, FAIL }

/**
 * Определяет, закончился ли синтез TTS-фрагмента.
 *
 * Главное правило: «размер файла перестал меняться» **не** доказывает
 * завершение — временная пауза записи даёт такой же стабильный размер. Если
 * принять её за готовность, в мердж попадёт оборванный WAV, и слышен прыжок
 * с середины абзаца на следующий. Поэтому завершением считается только:
 *
 *  - колбэк движка `onDone` ([done]);
 *  - движок больше не говорит ([engineSpeaking] == false), файл перестал
 *    расти и контейнер дописан ([containerComplete]).
 *
 * Логика вынесена из `TtsAudioSynthesizer` в чистый класс, чтобы её можно
 * было покрыть JVM-тестами без Android/TextToSpeech.
 */
internal class SynthesisCompletionGate(
    private val stableRoundsRequired: Int = DEFAULT_STABLE_ROUNDS,
    private val minAudioBytes: Long = MIN_AUDIO_BYTES,
) {
    private var lastSize = -1L
    private var stableRounds = 0

    /**
     * @param done движок прислал `onDone`;
     * @param failed движок прислал `onError`;
     * @param fileSize текущий размер файла (-1, если файла ещё нет);
     * @param engineSpeaking движок всё ещё синтезирует/говорит;
     * @param containerComplete ленивая проверка, что контейнер дописан; вызывается
     *   только когда размер перестал расти, чтобы не читать заголовок на каждый опрос.
     */
    fun evaluate(
        done: Boolean,
        failed: Boolean,
        fileSize: Long,
        engineSpeaking: Boolean,
        containerComplete: () -> Boolean,
    ): SynthesisCompletion {
        if (failed) return SynthesisCompletion.FAIL
        if (done) return SynthesisCompletion.ACCEPT

        if (fileSize > minAudioBytes) {
            stableRounds = if (fileSize == lastSize) stableRounds + 1 else 0
            if (stableRounds >= stableRoundsRequired &&
                !engineSpeaking &&
                containerComplete()
            ) {
                return SynthesisCompletion.ACCEPT
            }
        } else {
            stableRounds = 0
        }
        lastSize = fileSize
        return SynthesisCompletion.WAIT
    }

    internal companion object {
        const val MIN_AUDIO_BYTES = 44L
        const val DEFAULT_STABLE_ROUNDS = 2
    }
}
