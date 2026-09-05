package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.media.MediaMetadataRetriever
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsTimeline
import my.noveldokusha.text_to_speech.TtsTimelineAudio
import my.noveldokusha.text_to_speech.TtsTimelineChapter
import my.noveldokusha.text_to_speech.TtsTimelineParagraph
import my.noveldokusha.text_to_speech.TtsTimelineRange
import my.noveldokusha.text_to_speech.TtsTimelineText
import my.noveldokusha.text_to_speech.TtsTextPreparer
import my.noveldokusha.text_to_speech.timelineToJson
import java.io.File
import kotlin.math.roundToLong

/** Builds an approximate timing map for a pre-existing downloaded audio file. */
object CinematicExistingAudioTimeline {
    private const val PARAGRAPH_SEPARATOR = "\n\n"

    fun writeApproximateTimeline(
        context: Context,
        request: TtsAudioExportRequest,
        audioUri: android.net.Uri,
        audioFileName: String,
        chapterText: String,
        regexRules: List<RegexRule>,
        outputFile: File,
    ) {
        val durationMs = readDurationMs(context, audioUri)
            ?: throw IllegalStateException("Could not read duration of existing audio: $audioFileName")
        require(durationMs > 0L) { "Existing audio has no usable duration: $audioFileName" }

        val paragraphs = TtsTextPreparer.paragraphsFromBody(chapterText, regexRules)
        require(paragraphs.isNotEmpty()) {
            "Could not create a timing map because the chapter text is empty"
        }

        val preparedText = paragraphs.joinToString(PARAGRAPH_SEPARATOR)
        val totalCodePoints = preparedText.codePointCount(0, preparedText.length)
        require(totalCodePoints > 0) { "Could not create a timing map because the chapter text is empty" }

        val paragraphModels = mutableListOf<TtsTimelineParagraph>()
        var globalUtf16Start = 0
        var globalCpStart = 0
        var elapsedCp = 0

        paragraphs.forEachIndexed { index, paragraph ->
            val paragraphCp = paragraph.codePointCount(0, paragraph.length)
            val paragraphStartMs = ((elapsedCp.toDouble() / totalCodePoints) * durationMs).roundToLong()
            elapsedCp += paragraphCp
            val paragraphEndMs = ((elapsedCp.toDouble() / totalCodePoints) * durationMs).roundToLong()
            val paragraphEndCp = globalCpStart + paragraphCp

            val ranges = buildWordRanges(
                text = paragraph,
                globalStartCp = globalCpStart,
                startMs = paragraphStartMs,
                endMs = paragraphEndMs,
            )

            paragraphModels += TtsTimelineParagraph(
                index = index,
                text = paragraph,
                startChar = globalCpStart,
                endChar = paragraphEndCp,
                startMs = paragraphStartMs.coerceIn(0L, durationMs).toInt(),
                endMs = paragraphEndMs.coerceIn(paragraphStartMs, durationMs).toInt(),
                ranges = ranges,
            )

            globalUtf16Start += paragraph.length
            if (index < paragraphs.lastIndex) globalUtf16Start += PARAGRAPH_SEPARATOR.length
            globalCpStart = paragraphEndCp + if (index < paragraphs.lastIndex) {
                PARAGRAPH_SEPARATOR.codePointCount(0, PARAGRAPH_SEPARATOR.length)
            } else 0
        }

        val timeline = TtsTimeline(
            schemaVersion = TtsTimeline.CURRENT_SCHEMA_VERSION,
            chapter = TtsTimelineChapter(
                novelTitle = request.novelTitle,
                chapterTitle = request.chapterTitle,
                chapterIndex = request.chapterIndex,
                source = request.source.name,
                audioFile = audioFileName,
            ),
            audio = TtsTimelineAudio(
                format = audioFileName.substringAfterLast('.', "audio"),
                sampleRate = 0,
                channels = 0,
                durationMs = durationMs.toInt(),
            ),
            text = TtsTimelineText(
                preparedText = preparedText,
                characterCount = totalCodePoints,
            ),
            paragraphs = paragraphModels,
        )

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(timelineToJson(timeline), Charsets.UTF_8)
    }

    private fun buildWordRanges(
        text: String,
        globalStartCp: Int,
        startMs: Long,
        endMs: Long,
    ): List<TtsTimelineRange> {
        val tokens = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            tokens += start to i
        }
        if (tokens.isEmpty()) return emptyList()

        val totalWeight = tokens.sumOf { (s, e) -> text.codePointCount(s, e).coerceAtLeast(1) }
        var consumedWeight = 0

        return tokens.mapIndexed { index, (startUtf16, endUtf16) ->
            val tokenWeight = text.codePointCount(startUtf16, endUtf16).coerceAtLeast(1)
            val rangeStartMs = startMs + ((endMs - startMs).coerceAtLeast(0L) * consumedWeight / totalWeight)
            consumedWeight += tokenWeight
            val rangeEndMs = if (index == tokens.lastIndex) {
                endMs
            } else {
                startMs + ((endMs - startMs).coerceAtLeast(0L) * consumedWeight / totalWeight)
            }
            val startCp = globalStartCp + text.codePointCount(0, startUtf16)
            val endCp = globalStartCp + text.codePointCount(0, endUtf16)
            TtsTimelineRange(
                startChar = startCp,
                endChar = endCp,
                startMs = rangeStartMs.coerceIn(startMs, endMs).toInt(),
                endMs = rangeEndMs.coerceIn(rangeStartMs, endMs).toInt(),
                text = text.substring(startUtf16, endUtf16),
            )
        }
    }

    private fun readDurationMs(context: Context, uri: android.net.Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
