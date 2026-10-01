package my.noveldokusha.tooling.audiobook

/**
 * Формат итогового аудиофайла.
 *
 * WAV — только звук, потоковый мердж PCM-сегментов.
 * MP4 — звук плюс зацикленный визуальный ряд (см. [VisualSource]).
 */
enum class AudiobookFormat { WAV, MP4 }

/** Источник контента для озвучки. */
enum class AudiobookContentMode { ORIGINAL, TRANSLATION }

/** Тип визуального источника для MP4. */
enum class VisualSource { IMAGE, VIDEO, GIF }

/**
 * Запрос на экспорт аудиокниги.
 *
 * Все поля — сериализуемые примитивы/строки, чтобы запрос целиком помещался
 * в `Data` воркера и переживал пересоздание процесса.
 */
data class AudiobookExportRequest(
    val bookUrl: String,
    val bookTitle: String,
    val format: AudiobookFormat,
    val contentMode: AudiobookContentMode,
    val sourceLang: String,
    val targetLang: String,
    /** Позиция первой главы (0-based, как `Chapter.position`). */
    val startPosition: Int,
    /** Позиция последней главы включительно (0-based). */
    val endPosition: Int,
    // Снимок настроек TTS на момент постановки задачи, чтобы
    // изменение настроек посреди экспорта не влияло на текущий job.
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
    val visualUri: String?,
    val visualSource: VisualSource?,
    val visualSourceName: String?,
    val treeUri: String,
) {
    val chapterCount: Int get() = if (endPosition >= startPosition) endPosition - startPosition + 1 else 0
}

/** Подготовленная к озвучке глава: заголовок + логические абзацы. */
data class AudiobookChapterData(
    val position: Int,
    val url: String,
    val title: String,
    val paragraphs: List<String>,
)

/** Этап экспорта — для прогресса и уведомлений. */
enum class AudiobookStage(val weight: Int) {
    PREPARING(0),
    SYNTHESIZING(10),
    MERGING_AUDIO(20),
    PREPARING_VISUAL(30),
    CREATING_MP4(40),
    WRITING_JSON(50),
    FINALIZING(55),
    COMPLETED(100),
}

/** Реальный прогресс экспорта (без фейковых значений). */
data class AudiobookExportProgress(
    val stage: AudiobookStage,
    /** Глава (1-based внутри выбранного диапазона), 0 — до старта синтеза. */
    val currentChapter: Int,
    val totalChapters: Int,
    val chapterTitle: String,
    /** Абзац внутри текущей главы (1-based), 0 — на этапе intro. */
    val currentParagraph: Int,
    val paragraphsInChapter: Int,
    /** Процент 0..100, вычисленный по объёму синтезированного текста. */
    val percent: Int,
    /** Накопленная длительность озвучки в мс. */
    val generatedAudioMs: Long,
    /** Оценка оставшегося времени в мс на основе фактического темпа. */
    val estimatedRemainingMs: Long?,
)

/** Метаданные нормализованного визуального сегмента для MP4. */
data class VisualSegmentInfo(
    val type: VisualSource,
    val sourceName: String,
    val loop: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
)
