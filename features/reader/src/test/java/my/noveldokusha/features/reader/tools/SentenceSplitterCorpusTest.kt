package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.text.SentenceSplitter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Diagnostic lane (issue-209): runs the real [SentenceSplitter] over downloaded
 * corpus chapters and writes human-readable outputs for manual review:
 *  - kotlin_split_report.md  — per-file stats, long-unsplit paragraphs, split samples
 *  - kotlin_split_details.txt — full dump of every paragraph and its segments
 *
 * Skipped (Assumptions) when the corpus directory is not present, so CI without
 * the corpus still passes. Corpus location: -Dcorpus.dir=<path>, else a
 * ".tmp-issue209" directory found from the repo root.
 */
class SentenceSplitterCorpusTest {

    /** closing->opening pairs, replicated from SentenceSplitter for balance counts. */
    private val QUOTE_PAIRS = listOf(
        '"' to '"',
        '»' to '«',
        '\u201D' to '\u201C', // ” “
        '\u2019' to '\u2018', // ’ ‘
        '\u300D' to '\u300C', // 」 「
        '\u300F' to '\u300E', // 』 『
        '\u300B' to '\u300A', // 》 《
        '\u203A' to '\u2039'  // › ‹
    )

    /** Script terminators (always-valid boundaries), replicated from SentenceSplitter. */
    private val SCRIPT_TERMINATORS = setOf(
        '\u3002', '\uFF01', '\uFF1F', '\uFF0E',
        '\u061F', '\u06D4', '\u0964', '\u0965', '\u0589',
        '\u104A', '\u104B', '\u1362', '\u1367', '\u1368'
    )

    private data class ParaStat(
        val paragraph: String,
        val segments: List<String>,
        val reason: SentenceSplitter.UnsplitReason?,
        val label: String,
        val rejections: List<SentenceSplitter.BoundaryRejection>
    )

    /**
     * Observable classification, mirrors [SentenceSplitter.splitParagraph] output:
     * SPLIT = more than one segment; TOO_SHORT / STATUS_BLOCK / DASH_DIALOGUE /
     * NO_VALID_BOUNDARY as diagnosed; TRIVIAL_BOUNDARY = boundaries existed but the
     * only accepted one sits at the very end, so the paragraph is returned unchanged
     * (single segment) — no effective split.
     */
    private fun label(segments: List<String>, reason: SentenceSplitter.UnsplitReason?): String = when {
        segments.size > 1 -> "SPLIT"
        reason == null -> "TRIVIAL_BOUNDARY"
        else -> reason.name
    }

    private data class FileStats(val name: String, val paras: List<ParaStat>)

    /** Short-paragraph (<250) measurement for the threshold decision. */
    private data class ShortStat(
        val paragraph: String,
        val hasTwoBoundaries: Boolean,
        val wouldSplit: Boolean
    ) {
        val under100: Boolean get() = paragraph.length < 100
    }

    private data class ShortFileStats(val name: String, val shorts: List<ShortStat>)

    @Test
    fun corpusDiagnostics_writeReportAndDetails() {
        val corpusDir = resolveCorpusDir()
        assumeTrue("Corpus dir not found (pass -Dcorpus.dir=...); skipping.", corpusDir != null)
        val dir = corpusDir!!

        val files = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().endsWith(".txt") }
                .filter { !it.fileName.toString().startsWith("kotlin_split_") } // skip our own outputs
                .sorted().toList()
        }
        assumeTrue("No .txt chapters found in $dir", files.isNotEmpty())

        val allStats = files.map { analyze(it) }
        val shortStats = files.map { analyzeShorts(it) }

        val report = StringBuilder()
        val details = StringBuilder()
        renderReportHeader(report, dir)
        renderStatsTable(report, allStats)
        for (stats in allStats) {
            renderFileSection(report, stats)
            renderDetails(details, stats)
        }
        renderShortParagraphSection(report, shortStats)

        val reportPath = dir.resolve("kotlin_split_report.md")
        val detailsPath = dir.resolve("kotlin_split_details.txt")
        Files.writeString(reportPath, report.toString())
        Files.writeString(detailsPath, details.toString())

        assertTrue("Report file is empty: $reportPath", Files.size(reportPath) > 0)
        assertTrue("Details file is empty: $detailsPath", Files.size(detailsPath) > 0)
    }

    // ------------------------------------------------------------------ corpus

    private fun resolveCorpusDir(): Path? {
        System.getProperty("corpus.dir")?.let { p -> Paths.get(p).takeIf { Files.isDirectory(it) }?.let { return it } }
        var dir: Path? = Paths.get("").toAbsolutePath().normalize()
        repeat(6) {
            if (dir == null) return null
            val candidate = dir.resolve(".tmp-issue209")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        return null
    }

    private fun loadParagraphs(file: Path): List<String> =
        Files.readString(file)
            .replace("\uFEFF", "")
            .split(Regex("\r?\n\\s*\r?\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun analyze(file: Path): FileStats {
        val paras = loadParagraphs(file).map { p ->
            val segments = SentenceSplitter.splitParagraph(p)
            val reason = SentenceSplitter.diagnose(p)
            ParaStat(
                paragraph = p,
                segments = segments,
                reason = reason,
                label = label(segments, reason),
                rejections = SentenceSplitter.boundaryRejections(p)
            )
        }
        return FileStats(file.fileName.toString(), paras)
    }

    /**
     * Threshold-decision measurement over the <250 band: how many short paragraphs
     * have >= 2 boundary candidates, and how many would actually produce >= 2
     * segments if the threshold did not gate them.
     */
    private fun analyzeShorts(file: Path): ShortFileStats {
        val shorts = loadParagraphs(file)
            .filter { it.length < 250 }
            .map { p ->
                ShortStat(
                    paragraph = p,
                    hasTwoBoundaries = SentenceSplitter.findValidBoundaries(p).size >= 2,
                    wouldSplit = SentenceSplitter.splitIgnoringMinLength(p).size >= 2
                )
            }
        return ShortFileStats(file.fileName.toString(), shorts)
    }

    // ------------------------------------------------------------------ helpers

    private fun terminatorSummary(p: String): String {
        val basic = listOf('.', '!', '?').joinToString("/") { ch -> "${p.count { it == ch }}" }
        val script = SCRIPT_TERMINATORS.count { ch -> ch in p }
        return "basic[$basic] script=$script"
    }

    private fun quoteBalance(p: String): String =
        QUOTE_PAIRS.joinToString(" ") { (close, open) ->
            val o = p.count { it == open }
            val c = p.count { it == close }
            if (o == 0 && c == 0) "" else "${open}${close}:$o/$c"
        }.trim().ifEmpty { "none" }

    private fun isLong(stat: ParaStat) = stat.paragraph.length >= 250

    private fun longUnsplit(stats: FileStats) = stats.paras.filter { it.label != "SPLIT" && isLong(it) }

    private fun rejectionHistogram(paras: List<ParaStat>): Map<String, Int> =
        paras.flatMap { it.rejections.map { r -> r.reason } }.groupingBy { it }.eachCount()

    /** Long unsplit paragraphs where boundary rejection is the cause: the "should have split" set. */
    private fun boundaryBlockedLong(stats: FileStats) = stats.paras.filter {
        isLong(it) && (it.label == "NO_VALID_BOUNDARY" || it.label == "TRIVIAL_BOUNDARY")
    }

    private fun first400(p: String): String = p.take(400) + if (p.length > 400) " …" else ""

    private fun flag(s: String): String = when {
        s.isEmpty() -> " [!EMPTY]"
        s.length < 20 -> " [!<20, len=${s.length}]"
        else -> ""
    }

    // ------------------------------------------------------------------ report

    private fun renderReportHeader(out: StringBuilder, dir: Path) {
        out.appendLine("# Kotlin SentenceSplitter — corpus report")
        out.appendLine()
        out.appendLine("Generated by `SentenceSplitterCorpusTest` from corpus dir: `$dir`")
        out.appendLine()
        out.appendLine("Labels: SPLIT (>1 segment); TOO_SHORT (para < MIN_PARAGRAPH_LENGTH = " +
            "${SentenceSplitter.minParagraphLength}); STATUS_BLOCK (litRPG); " +
            "DASH_DIALOGUE (starts with —/–); NO_VALID_BOUNDARY (no candidate passed rules a–g); " +
            "TRIVIAL_BOUNDARY (a boundary exists but only at the very end → paragraph returned unchanged).")
        out.appendLine()
    }

    private fun renderStatsTable(out: StringBuilder, allStats: List<FileStats>) {
        out.appendLine("## Per-file stats")
        out.appendLine()
        out.appendLine("| file | paragraphs | split (>1 seg) | short (<MIN) | long unsplit (>=250) | status | dash | no-boundary | trivial |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (s in allStats) {
            val long = longUnsplit(s)
            val byLabel = long.groupingBy { it.label }.eachCount()
            out.appendLine(
                "| ${s.name} | ${s.paras.size} | ${s.paras.count { it.label == "SPLIT" }} | " +
                    "${s.paras.count { it.label == "TOO_SHORT" }} | " +
                    "${long.size} | ${byLabel["STATUS_BLOCK"] ?: 0} | " +
                    "${byLabel["DASH_DIALOGUE"] ?: 0} | " +
                    "${byLabel["NO_VALID_BOUNDARY"] ?: 0} | " +
                    "${byLabel["TRIVIAL_BOUNDARY"] ?: 0} |"
            )
        }
        out.appendLine()
    }

    private fun renderFileSection(out: StringBuilder, stats: FileStats) {
        out.appendLine("## ${stats.name}")
        out.appendLine()

        val long = longUnsplit(stats)
        val blocked = boundaryBlockedLong(stats)
        val hist = rejectionHistogram(blocked)
        val dominant = hist.maxByOrNull { it.value }

        out.appendLine("- paragraphs: ${stats.paras.size}; real splits (>1 segment): " +
            "${stats.paras.count { it.label == "SPLIT" }}; long unsplit (>=250): ${long.size}.")
        out.appendLine("- long-unsplit labels: ${
            long.groupingBy { it.label }.eachCount().entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        }.")
        if (blocked.isNotEmpty()) {
            out.appendLine("- boundary-rejection histogram (over ${blocked.size} boundary-blocked long paras): " +
                hist.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" } + ".")
            if (dominant != null) {
                out.appendLine("- **Dominant rejection reason: `${dominant.key}` (${dominant.value} rejections).**")
            }
        } else {
            out.appendLine("- boundary-blocked long paragraphs: none.")
        }
        out.appendLine()

        // Long unsplit paragraphs, detail.
        out.appendLine("### Long unsplit paragraphs (>=250 chars)")
        if (long.isEmpty()) {
            out.appendLine("(none)")
        } else {
            for ((idx, stat) in long.withIndex()) {
                out.appendLine("- `#${idx + 1}` label=${stat.label}${if (stat.reason != null) " reason=${stat.reason}" else ""} " +
                    "len=${stat.paragraph.length} " +
                    "term[${terminatorSummary(stat.paragraph)}] quotes[${quoteBalance(stat.paragraph)}]")
                out.appendLine("  - text: `${first400(stat.paragraph)}`")
                if (stat.rejections.isNotEmpty()) {
                    out.appendLine("  - rejections: " + stat.rejections.joinToString(", ") { "pos=${it.terminatorPos} ${it.reason}" })
                }
            }
        }
        out.appendLine()

        // Split samples: first 5 per file.
        out.appendLine("### Split samples (first 5, only real splits)")
        val splitParas = stats.paras.filter { it.label == "SPLIT" }.take(5)
        if (splitParas.isEmpty()) {
            out.appendLine("(none)")
        } else {
            for (stat in splitParas) {
                out.appendLine("- len=${stat.paragraph.length} -> ${stat.segments.size} segments:")
                for ((segIdx, seg) in stat.segments.withIndex()) {
                    out.appendLine("  - `[$segIdx]` (len=${seg.length})${flag(seg)} ${first400(seg)}")
                }
            }
        }
        out.appendLine()
    }

    // ------------------------------------------------------------------ measurement

    /** Threshold-decision section: what would happen to <250-char paragraphs without the gate. */
    private fun renderShortParagraphSection(out: StringBuilder, shortStats: List<ShortFileStats>) {
        out.appendLine("## Short-paragraph potential splits (band < 250)")
        out.appendLine()
        out.appendLine("| file | short (<250) | >=2 boundary candidates | would split (>=2 seg) | of which <100 | of which 100-249 |")
        out.appendLine("|---|---|---|---|---|---|")
        var totalShorts = 0
        var totalTwoBounds = 0
        var totalWouldSplit = 0
        var totalWouldSplitUnder100 = 0
        for (s in shortStats) {
            val twoBounds = s.shorts.count { it.hasTwoBoundaries }
            val wouldSplit = s.shorts.count { it.wouldSplit }
            val wouldSplitUnder100 = s.shorts.count { it.wouldSplit && it.under100 }
            totalShorts += s.shorts.size
            totalTwoBounds += twoBounds
            totalWouldSplit += wouldSplit
            totalWouldSplitUnder100 += wouldSplitUnder100
            out.appendLine(
                "| ${s.name} | ${s.shorts.size} | $twoBounds | $wouldSplit | $wouldSplitUnder100 | ${wouldSplit - wouldSplitUnder100} |"
            )
        }
        out.appendLine(
            "| **TOTAL** | $totalShorts | $totalTwoBounds | $totalWouldSplit | $totalWouldSplitUnder100 | ${totalWouldSplit - totalWouldSplitUnder100} |"
        )
        out.appendLine()
        out.appendLine("Decision rule (hard): corpus-wide count of <250-char paragraphs that would split")
        out.appendLine("into >= 2 segments >= 50 -> MIN_PARAGRAPH_LENGTH = 100; below that -> keep 250.")
        val decision = if (totalWouldSplit >= 50) "CHANGE to 100" else "KEEP 250"
        out.appendLine("**Decision: corpus-wide would-split = $totalWouldSplit >= 50 -> $decision.**")
        out.appendLine("Current MIN_PARAGRAPH_LENGTH = ${SentenceSplitter.minParagraphLength}.")
        out.appendLine()
    }

    // ------------------------------------------------------------------ details

    private fun renderDetails(out: StringBuilder, stats: FileStats) {
        out.appendLine("=".repeat(90))
        out.appendLine("FILE: ${stats.name}")
        out.appendLine("=".repeat(90))
        for ((idx, stat) in stats.paras.withIndex()) {
            out.appendLine()
            out.appendLine("--- paragraph #${idx + 1} | len=${stat.paragraph.length} | label=${stat.label}" +
                (if (stat.reason != null) " | reason=${stat.reason}" else "") + " ---")
            out.appendLine(stat.paragraph)
            if (stat.label == "SPLIT") {
                out.appendLine(">>> SEGMENTS (${stat.segments.size}):")
                for ((segIdx, seg) in stat.segments.withIndex()) {
                    out.appendLine("$segIdx | $seg")
                }
            } else {
                out.appendLine(">>> UNSPLIT:${stat.label}")
            }
        }
        out.appendLine()
    }
}