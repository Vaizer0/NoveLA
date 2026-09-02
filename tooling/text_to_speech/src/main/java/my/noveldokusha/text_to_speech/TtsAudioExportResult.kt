package my.noveldokusha.text_to_speech

import java.io.File

/**
 * Результат одного экспорта аудио главы: сам аудиофайл И синхронизационная
 * временная шкала, построенная в ТОТ ЖЕ сеанс синтеза, что и аудио.
 *
 * Успешный синхронизированный экспорт означает наличие обоих артефактов; отсутствие
 * валидного timeline — неудача (см. TtsAudioExportWorker/TtsAudioExporter).
 */
data class TtsAudioExportResult(
    val audioFile: File,
    val timeline: TtsTimeline,
)
