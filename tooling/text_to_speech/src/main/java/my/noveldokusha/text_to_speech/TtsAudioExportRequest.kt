package my.noveldokusha.text_to_speech

import my.noveldokusha.core.appPreferences.TtsAudioSource

/**
 * Неизменяемый снимок параметров одного экспорта аудио главы.
 *
 * Формируется один раз в момент запуска загрузки из текущего профиля
 * «Загрузка аудио» (TTS_AUDIO_DOWNLOAD_*) и не меняется в ходе выполнения:
 * последующее изменение настроек не влияет на уже поставленный в очередь экспорт.
 *
 * Заметьте: здесь НЕ хранится тело главы — текст берётся воркером из БД
 * (ChapterBody/ChapterTranslation) по [chapterUrl] в момент выполнения.
 * Класс не аннотирован @Serializable: воркер маршалит его в плоские
 * WorkManager Data-ключи (как BookExportWorker).
 */
data class TtsAudioExportRequest(
    /** Уникальный id задачи (детерминированный для повторных запросов). */
    val jobId: String,
    val novelTitle: String,
    val novelUrl: String,
    val chapterUrl: String,
    val chapterTitle: String,
    /** Позиция главы в списке (для имени файла "Chapter N ..."). */
    val chapterIndex: Int,
    val source: TtsAudioSource,
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
    /** SAF tree URI папки назначения. */
    val outputDirectoryUri: String,
    /** Формат аудио ("wav" для V1). */
    val format: String = TtsAudioFormat.WAV,
) {
    /**
     * Детерминированный идентификатор экспорта для дедупликации/перезаписи:
     * один и тот же (книга, глава, источник) → один и тот же jobId.
     */
    companion object {
        fun makeJobId(novelUrl: String, chapterUrl: String, source: TtsAudioSource): String {
            val raw = "$novelUrl::$chapterUrl::${source.name}"
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
            return "tts_audio_$sha"
        }
    }
}

/**
 * Поддерживаемые форматы аудиофайлов.
 *
 * V1 (TtsAudioExporter/WavWriter) умеет генерировать ТОЛЬКО WAV/PCM. Расширение
 * файла и MIME строго соответствуют этому формату. Любой другой формат ДОЛЖЕН
 * отсекаться либо на этапе постановки в очередь (принудительный WAV), либо явно
 * отклоняться воркером перед экспортом — никогда не создаётся файл с чужим
 * расширением (.m4a), содержащий WAV-данные.
 *
 * [M4A] зарезервирован для V2 (реальный MediaCodec/MediaMuxer AAC/M4A-экспортёр)
 * и в V1 не производится: попытка использовать его завершится отказом.
 */
object TtsAudioFormat {
    /** Единственный формат V1: WAV/PCM. */
    const val WAV = "wav"

    /** Будущий (V2) формат AAC/M4A. НЕ используется в V1. */
    const val M4A = "m4a"
}