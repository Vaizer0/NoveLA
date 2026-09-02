package my.noveldokusha.text_to_speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Неизменяемая модель «временной шкалы синхронизации» экспортированной аудио-главы.
 *
 * Рождается В ТОТ ЖЕ сеанс синтеза, который порождает WAV (native
 * [android.speech.tts.UtteranceProgressListener.onRangeStart] /
 * [android.speech.tts.UtteranceProgressListener.onBeginSynthesis] во время
 * [TtsAudioExporter.exportAudio]), поэтому описывает ровно тот аудиофайл, который
 * создан рядом. Не зависит от UI/Compа: готова к потреблению внешним рендерером
 * «audio + timeline.json → синхронизированное видео».
 *
 * Все строки/смещения используют индексацию Kotlin/Java String (char offset).
 * Все времена — абсолютные целые миллисекунды относительно начала аудиофайла,
 * монотонные, без wall-clock/uptime.
 */
@Serializable
data class TtsTimeline(
    @SerialName("schemaVersion")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("chapter")
    val chapter: TtsTimelineChapter,
    @SerialName("audio")
    val audio: TtsTimelineAudio,
    @SerialName("text")
    val text: TtsTimelineText,
    @SerialName("paragraphs")
    val paragraphs: List<TtsTimelineParagraph>,
) {
    companion object {
        /** Версия формата обмена (не версия приложения). */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Метаданные главы/книги в timeline. */
@Serializable
data class TtsTimelineChapter(
    @SerialName("novelTitle")
    val novelTitle: String,
    @SerialName("chapterTitle")
    val chapterTitle: String,
    @SerialName("chapterIndex")
    val chapterIndex: Int,
    @SerialName("source")
    val source: String,
    @SerialName("audioFile")
    val audioFile: String,
)

/** Сведения о сгенерированном аудио (WAV). */
@Serializable
data class TtsTimelineAudio(
    @SerialName("format")
    val format: String,
    @SerialName("sampleRate")
    val sampleRate: Int,
    @SerialName("channels")
    val channels: Int,
    @SerialName("durationMs")
    val durationMs: Int,
)

/** Полный текст, подготовленный к синтезу (тот, что реально был синтезирован). */
@Serializable
data class TtsTimelineText(
    @SerialName("preparedText")
    val preparedText: String,
    @SerialName("characterCount")
    val characterCount: Int,
)

/** Один абзац с его native-ranges. */
@Serializable
data class TtsTimelineParagraph(
    @SerialName("index")
    val index: Int,
    @SerialName("text")
    val text: String,
    @SerialName("startChar")
    val startChar: Int,
    @SerialName("endChar")
    val endChar: Int,
    @SerialName("startMs")
    val startMs: Int,
    @SerialName("endMs")
    val endMs: Int,
    @SerialName("ranges")
    val ranges: List<TtsTimelineRange>,
)

/**
 * Авторитетный native синхронизационный юнит: один [android.speech.tts.UtteranceProgressListener.onRangeStart].
 * Диапазон [startChar, endChar) — в [TtsTimeline.text.preparedText].
 */
@Serializable
data class TtsTimelineRange(
    @SerialName("startChar")
    val startChar: Int,
    @SerialName("endChar")
    val endChar: Int,
    @SerialName("startMs")
    val startMs: Int,
    @SerialName("endMs")
    val endMs: Int,
    @SerialName("text")
    val text: String,
    @SerialName("frameStart")
    val frameStart: Int? = null,
    @SerialName("frameEnd")
    val frameEnd: Int? = null,
)

// ── Сериализация consumable внешним рендерером ──────────────────────────────

/**
 * Общий детерминированный JSON-кодек для [TtsTimeline].
 * - UTF-8, без wall-clock/случайных id/путей — только стабильные поля схемы.
 * - Порядок полей/массивов строго как в схеme (не зависит от порядка хранения в памяти).
 */
val TIMELINE_JSON: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    // Поля схемы обязательны: frameEnd пишется как null, когда native колбэк не даёт
    // «конечный» frame (последний range куска), а не опускается.
    explicitNulls = true
}

/** Сериализация timeline в детерминированный JSON-текст. */
fun timelineToJson(timeline: TtsTimeline): String =
    TIMELINE_JSON.encodeToString(TtsTimeline.serializer(), timeline)

/** Десериализация timeline из JSON (round-trip и внешние потребители). */
fun timelineFromJson(json: String): TtsTimeline =
    TIMELINE_JSON.decodeFromString(TtsTimeline.serializer(), json)
