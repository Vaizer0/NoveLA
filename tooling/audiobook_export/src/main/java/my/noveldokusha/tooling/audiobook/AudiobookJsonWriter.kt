package my.noveldokusha.tooling.audiobook

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

/** Конверт документа без таймлайна: таймлайн дописывается потоково. */
@Serializable
private data class JsonEnvelope(
    val schemaVersion: Int,
    val type: String,
    val format: String,
    val novel: JsonNovel,
    val content: JsonContent,
    val chapters: JsonChapters,
    val audio: JsonAudio,
    val visual: JsonVisual? = null,
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

    /** Компактная форма: одна глава — одна строка без переносов. */
    private val compactJson = Json {
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
            content = contentOf(contentMode, sourceLanguage, targetLanguage),
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
            visual = visual?.toJson(),
            timeline = timeline.map { it.toJson() },
        )
        return json.encodeToString(document)
    }

    /**
     * Потоково пишет JSON, не удерживая таймлайн целиком в памяти.
     *
     * Из `ChapterSpool` главы читаются по одной строке и вставляются в массив
     * `timeline`; таким образом на 1000+ глав в куче живёт не больше одной.
     */
    internal fun writeDocument(
        output: File,
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
        spool: ChapterSpool,
        visual: VisualSegmentInfo?,
    ) {
        val envelope = JsonEnvelope(
            schemaVersion = AUDIOBOOK_SCHEMA_VERSION,
            type = TYPE_AUDIOBOOK,
            format = format.name.lowercase(),
            novel = JsonNovel(title = novelTitle, url = novelUrl),
            content = contentOf(contentMode, sourceLanguage, targetLanguage),
            chapters = JsonChapters(
                start = startPosition + 1,
                end = endPosition + 1,
                count = spool.chapterCount,
            ),
            audio = JsonAudio(
                durationMs = totalDurationMs,
                sampleRateHz = sampleRateHz,
                channels = channels,
            ),
            visual = visual?.toJson(),
        )
        val header = compactJson.encodeToString(envelope)
        output.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(header.dropLast(1))
            writer.write(",\"timeline\":[")
            var first = true
            spool.forEachJsonLine { line ->
                if (first) first = false else writer.write(",")
                writer.write(line)
            }
            writer.write("]}")
        }
    }

    /** Компактный JSON одной главы для строки spool'а. */
    internal fun encodeChapter(chapter: AudiobookChapterTiming): String =
        compactJson.encodeToString(chapter.toJson())

    private fun contentOf(
        contentMode: AudiobookContentMode,
        sourceLanguage: String,
        targetLanguage: String?,
    ) = JsonContent(
        mode = if (contentMode == AudiobookContentMode.TRANSLATION) "translation" else "original",
        sourceLanguage = sourceLanguage.ifBlank { "und" },
        targetLanguage = targetLanguage?.takeIf { it.isNotBlank() },
    )

    private fun VisualSegmentInfo.toJson() = JsonVisual(
        type = type.name.lowercase(),
        sourceName = sourceName,
        loop = loop,
        durationMs = durationMs,
        width = width,
        height = height,
    )

    private fun AudiobookChapterTiming.toJson() = JsonChapter(
        chapterIndex = chapterIndex,
        title = title,
        startMs = span.startMs,
        endMs = span.endMs,
        durationMs = span.durationMs,
        intro = JsonIntro(
            startMs = intro.span.startMs,
            endMs = intro.span.endMs,
            durationMs = intro.span.durationMs,
            novelTitle = intro.novelTitle,
            chapterTitle = intro.chapterTitle,
        ),
        paragraphs = paragraphs.map { paragraph ->
            JsonParagraph(
                paragraphIndex = paragraph.paragraphIndex,
                startMs = paragraph.span.startMs,
                endMs = paragraph.span.endMs,
                durationMs = paragraph.span.durationMs,
                text = paragraph.text,
            )
        },
    )
}
