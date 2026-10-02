package my.noveldokusha.tooling.audiobook

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Дисковый буфер таймлайна: главы пишутся по одной строке (NDJSON).
 *
 * Держит в памяти только последнюю главу — её нужно придержать, потому что
 * фактическая длительность медиа становится известна лишь после сборки, и
 * именно последнюю главу выравнивают по ней (см. [alignLastChapter]).
 * Всё остальное сразу уходит на диск, поэтому 1000+ глав не растут в куче.
 */
internal class ChapterSpool(private val file: File) : AutoCloseable {

    private val writer = BufferedWriter(
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8),
    )
    private var held: AudiobookChapterTiming? = null
    private var closed = false

    /** Сколько глав уже принято (включая удерживаемую последнюю). */
    var chapterCount: Int = 0
        private set

    /** Принимает закрытую главу; предыдущую сбрасывает на диск. */
    fun add(chapter: AudiobookChapterTiming) {
        check(!closed) { "spool is already closed" }
        held?.let { writeLine(it) }
        held = chapter
        chapterCount++
    }

    /**
     * Выравнивает удерживаемую последнюю главу по [totalMs] и сбрасывает её.
     *
     * @return фактический конец таймлайна; сравните его с [totalMs]
     *   через [validateTotalDuration].
     */
    fun seal(totalMs: Long): Long {
        check(!closed) { "spool is already closed" }
        val last = held
        if (last == null) {
            writer.flush()
            return 0L
        }
        val aligned = alignLastChapter(last, totalMs)
        writeLine(aligned)
        held = null
        writer.flush()
        return aligned.span.endMs
    }

    /** Передаёт строки JSON по одной. Требует предварительного [seal]. */
    fun forEachJsonLine(action: (String) -> Unit) {
        check(held == null) { "seal() must be called before reading the spool" }
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotEmpty()) action(line)
        }
    }

    private fun writeLine(chapter: AudiobookChapterTiming) {
        writer.write(AudiobookJsonWriter.encodeChapter(chapter))
        writer.newLine()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writer.close() }
        runCatching { file.delete() }
    }
}
