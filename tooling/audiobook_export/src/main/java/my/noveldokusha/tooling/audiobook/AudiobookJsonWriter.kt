package my.noveldokusha.tooling.audiobook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Версия схемы таймлайна. Повышается только при несовместимых изменениях. */
const val AUDIOBOOK_SCHEMA_VERSION: Int = 1

private const val TYPE_AUDIOBOOK = "novela_audiobook"

@Serializable
private data class JsonNovel(val title: String, val url: String)

@Serializable
private data class JsonContent(
    val mode: String,
    val sourceLanguage: String,
    val targetLanguage: String? = null,
)

@Serializable
private data class JsonChapters(val start: Int, val end: Int, val count: Int)

@Serializable
private data class JsonAudio(
    val durationMs: Long,
    val sampleRateHz: Int,
    val channels: Int,
)

@Serializable
private data class JsonVisual(
    val type: String,
    val sourceName: String,
    val loop: Boolean,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
private data class JsonIntro(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val novelTitle: String,
    val chapterTitle: String,
)

@Serializable
private data class JsonParagraph(
    val paragraphIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val text: String,
)

@Serializable
private data class JsonChapter(
    val chapterIndex: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val intro: JsonIntro,
    val paragraphs: List<JsonParagraph>,
)

@Serializable
private data class JsonTimelineDocument(
    val schemaVersion: Int,
    val type: String,
    val format: String,
    val novel: JsonNovel,
    val content: JsonContent,
    val chapters: JsonChapters,
    val audio: JsonAudio,
    val visual: JsonVisual? = null,
    val timeline: List<JsonChapter>,
)

/**
 * Пишет JSON-таймлайн аудиокниги.
 *
 * Сериализация происходит **после** успешного завершения генерации медиа
 * и валидации таймлайна: частично корректный файл на диске не появляется.
 */
object AudiobookJsonWriter {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun buildDocument(
        novelTitle: String,
        novelUrl: String,
        format: AudiobookFormat,
        contentMode: AudiobookContentMode,
        sourceLanguage: String,
        targetLanguage: String?,
        startPosition: Int,
        endPosition: Int,
        totalDurationMs: Long,
        sampleRateHz: Int,
        channels: Int,
        timeline: List<AudiobookChapterTiming>,
        visual: VisualSegmentInfo?,
    ): String {
        val document = JsonTimelineDocument(
            schemaVersion = AUDIOBOOK_SCHEMA_VERSION,
            type = TYPE_AUDIOBOOK,
            format = format.name.lowercase(),
            novel = JsonNovel(title = novelTitle, url = novelUrl),
            content = JsonContent(
                mode = if (contentMode == AudiobookContentMode.TRANSLATION) "translation" else "original",
                sourceLanguage = sourceLanguage.ifBlank { "und" },
                targetLanguage = targetLanguage?.takeIf { it.isNotBlank() },
            ),
            chapters = JsonChapters(
                start = startPosition + 1,
                end = endPosition + 1,
                count = timeline.size,
            ),
            audio = JsonAudio(
                durationMs = totalDurationMs,
                sampleRateHz = sampleRateHz,
                channels = channels,
            ),
            visual = visual?.let {
                JsonVisual(
                    type = it.type.name.lowercase(),
                    sourceName = it.sourceName,
                    loop = it.loop,
                    durationMs = it.durationMs,
                    width = it.width,
                    height = it.height,
                )
            },
            timeline = timeline.map { chapter ->
                JsonChapter(
                    chapterIndex = chapter.chapterIndex,
                    title = chapter.title,
                    startMs = chapter.span.startMs,
                    endMs = chapter.span.endMs,
                    durationMs = chapter.span.durationMs,
                    intro = JsonIntro(
                        startMs = chapter.intro.span.startMs,
                        endMs = chapter.intro.span.endMs,
                        durationMs = chapter.intro.span.durationMs,
                        novelTitle = chapter.intro.novelTitle,
                        chapterTitle = chapter.intro.chapterTitle,
                    ),
                    paragraphs = chapter.paragraphs.map { paragraph ->
                        JsonParagraph(
                            paragraphIndex = paragraph.paragraphIndex,
                            startMs = paragraph.span.startMs,
                            endMs = paragraph.span.endMs,
                            durationMs = paragraph.span.durationMs,
                            text = paragraph.text,
                        )
                    },
                )
            },
        )
        return json.encodeToString(document)
    }
}
