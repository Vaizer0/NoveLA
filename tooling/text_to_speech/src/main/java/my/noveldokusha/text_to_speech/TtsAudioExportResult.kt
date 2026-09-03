package my.noveldokusha.text_to_speech

import java.io.File

/**
 * Результат одного экспорта аудио главы: сам аудиофайл И синхронизационная
 * временная шкала, построенная в ТОТ ЖЕ сеанс синтеза, что и аудио.
 *
 * Аудио — главный артефакт: сбой сериализации/записи timeline НЕ валит экспорт
 * (воркер логирует и продолжает только с аудио — см. TtsAudioExportWorker,
 * коммит "never let timeline capture fail the audio export").
 */
data class TtsAudioExportResult(
    val audioFile: File,
    val timeline: TtsTimeline,
)
