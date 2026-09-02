package my.noveldokusha.text_to_speech

/**
 * Provenance for transformed text. All indices are Android/Kotlin UTF-16 code-unit indices.
 * Each output range points to the source range which produced it; replacements map to the full
 * matched range rather than pretending a character-for-character mapping exists.
 */
data class TextProvenance(val outputStart: Int, val outputEnd: Int, val sourceStart: Int, val sourceEnd: Int)

data class MappedText(val text: String, val provenance: List<TextProvenance>) {
    fun sourceForOutput(start: Int, end: Int): TextProvenance? {
        if (text.isEmpty()) return null
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return provenance.filter { it.outputEnd > s && it.outputStart < e }
            .let { hits ->
                if (hits.isEmpty()) null else TextProvenance(
                    outputStart = hits.minOf { it.outputStart }.coerceAtLeast(s),
                    outputEnd = hits.maxOf { it.outputEnd }.coerceAtMost(e),
                    sourceStart = hits.minOf { it.sourceStart },
                    sourceEnd = hits.maxOf { it.sourceEnd },
                )
            }
    }
}

/** A small deterministic transformation primitive used by the video mapping layer. */
object TtsVideoTextMapper {
    fun identity(text: String): MappedText =
        MappedText(text, if (text.isEmpty()) emptyList() else listOf(TextProvenance(0, text.length, 0, text.length)))

    fun replaceRange(input: MappedText, start: Int, end: Int, replacement: String): MappedText {
        require(start in 0..input.text.length && end in start..input.text.length)
        val source = input.sourceForOutput(start, end)
        val prefix = input.text.substring(0, start)
        val suffix = input.text.substring(end)
        val result = prefix + replacement + suffix
        val shift = replacement.length - (end - start)
        val out = buildList {
            input.provenance.forEach { p ->
                when {
                    p.outputEnd <= start -> add(p)
                    p.outputStart >= end -> add(p.copy(outputStart = p.outputStart + shift, outputEnd = p.outputEnd + shift))
                    else -> Unit
                }
            }
            if (replacement.isNotEmpty() && source != null) {
                add(TextProvenance(start, start + replacement.length, source.sourceStart, source.sourceEnd))
            }
        }.sortedBy { it.outputStart }
        return MappedText(result, out)
    }

    fun normalizeWhitespace(input: MappedText): MappedText {
        val text = input.text
        if (text.isEmpty()) return input
        val sb = StringBuilder(text.length)
        val out = ArrayList<TextProvenance>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!c.isWhitespace()) {
                val start = sb.length
                sb.append(c)
                val source = input.sourceForOutput(i, i + 1)
                if (source != null) out += TextProvenance(start, sb.length, source.sourceStart, source.sourceEnd)
                i++
            } else {
                val runStart = i
                while (i < text.length && text[i].isWhitespace()) i++
                val source = input.sourceForOutput(runStart, i)
                if (source != null) {
                    val start = sb.length
                    sb.append(' ')
                    out += TextProvenance(start, sb.length, source.sourceStart, source.sourceEnd)
                }
            }
        }
        return MappedText(sb.toString(), out)
    }

    fun trim(input: MappedText): MappedText {
        var start = 0
        var end = input.text.length
        while (start < end && input.text[start].isWhitespace()) start++
        while (end > start && input.text[end - 1].isWhitespace()) end--
        if (start == 0 && end == input.text.length) return input
        val text = input.text.substring(start, end)
        val mapping = input.provenance.mapNotNull { p ->
            val s = maxOf(p.outputStart, start)
            val e = minOf(p.outputEnd, end)
            if (e <= s) null else TextProvenance(s - start, e - start, p.sourceStart, p.sourceEnd)
        }
        return MappedText(text, mapping)
    }
}

data class VideoDisplayMapping(
    val sourceText: String,
    val preparedText: MappedText,
    val displayText: MappedText,
    val blockId: String,
) {
    /** Resolves a TTS/prepared UTF-16 range directly to the visible display range. */
    fun displayRangeForPrepared(start: Int, end: Int): IntRange? {
        val source = preparedText.sourceForOutput(start, end) ?: return null
        val hits = displayText.provenance.filter {
            it.sourceEnd > source.sourceStart && it.sourceStart < source.sourceEnd
        }
        if (hits.isEmpty()) return null
        val ds = hits.minOf { it.outputStart }
        val de = hits.maxOf { it.outputEnd }
        return ds until de
    }
}
