package my.noveldokusha.text_to_speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Immutable synchronization timeline for an exported WAV chapter.
 *
 * The timeline is produced by the same TTS synthesis session that creates the WAV,
 * so it describes that exact audio stream and can be consumed by the cinematic
 * renderer.
 *
 * Character offsets in the interchange format are Unicode code-point offsets,
 * matching the native cinematic renderer. Android/Kotlin TTS callbacks may use
 * UTF-16 String indices internally; conversion is performed before serialization.
 * Times are absolute integer milliseconds from the beginning of the WAV.
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
        /** Version of the exchange format, not the application version. */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

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

/** Exact prepared text displayed by the cinematic renderer. */
@Serializable
data class TtsTimelineText(
    @SerialName("preparedText")
    val preparedText: String,
    @SerialName("characterCount")
    val characterCount: Int,
)

/** One paragraph with native timing ranges. */
@Serializable
data class TtsTimelineParagraph(
    @SerialName("index")
    val index: Int,
    @SerialName("text")
    val text: String,
    /** Unicode code-point offset in [TtsTimelineText.preparedText]. */
    @SerialName("startChar")
    val startChar: Int,
    /** Exclusive Unicode code-point offset in [TtsTimelineText.preparedText]. */
    @SerialName("endChar")
    val endChar: Int,
    @SerialName("startMs")
    val startMs: Int,
    @SerialName("endMs")
    val endMs: Int,
    @SerialName("ranges")
    val ranges: List<TtsTimelineRange>,
)

/** One native TTS synchronization unit. */
@Serializable
data class TtsTimelineRange(
    /** Inclusive Unicode code-point offset. */
    @SerialName("startChar")
    val startChar: Int,
    /** Exclusive Unicode code-point offset. */
    @SerialName("endChar")
    val endChar: Int,
    @SerialName("startMs")
    val startMs: Int,
    @SerialName("endMs")
    val endMs: Int,
    val text: String,
    @SerialName("frameStart")
    val frameStart: Int? = null,
    @SerialName("frameEnd")
    val frameEnd: Int? = null,
)

/** Stable JSON codec for the external cinematic renderer. */
val TIMELINE_JSON: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = true
}

fun timelineToJson(timeline: TtsTimeline): String =
    TIMELINE_JSON.encodeToString(TtsTimeline.serializer(), timeline)

fun timelineFromJson(json: String): TtsTimeline =
    TIMELINE_JSON.decodeFromString(TtsTimeline.serializer(), json)
