package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.text.SentenceSplitter
import my.noveldokusha.features.reader.domain.ImgEntry
import my.noveldokusha.features.reader.domain.ReaderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitParagraphPairTest {

    private val chapterUrl = "https://example.com/books/test/chapters/1"

    // Sample taken from TextToItemsConverterTest: splits into exactly 4 sentences.
    private val fourSentenceParagraph = "The first sentence describes the quiet morning in great detail and the empty streets of the town. " +
        "The second one tells about the weather outside and the cold wind that came from the north. " +
        "The third sentence is a bit longer and adds some details about the people and the houses on that street. " +
        "The fourth and final sentence wraps everything up neatly and brings this paragraph to its natural end."

    // Same sentence count as fourSentenceParagraph, no colons, uppercase after each period.
    private val fourSentenceTranslated = "Первое предложение описывает спокойное утро и пустые улицы маленького городка. " +
        "Второе рассказывает о погоде на улице и о холодном ветре который дул с севера. " +
        "Третье немного длиннее и добавляет подробности о людях и домах вдоль этой дороги. " +
        "Четвёртое аккуратно завершает всё и приводит этот длинный абзац к его логическому концу."

    private val twoSentenceTranslated = "Первое длинное предложение второй пары очень подробно описывает всё что происходит вокруг спящего героя и не торопится с выводами. " +
        "Второе длинное предложение тоже растянуто и перечисляет множество подробностей о погоде о дороге и о людях которые идут мимо."

    private val secondFourSentenceParagraph = "A heavy rain started to fall over the fields beyond the village and the mud grew deep. " +
        "The travellers hurried along the narrow path while the sky turned dark and grey. " +
        "Somewhere behind the hills a distant thunder rolled across the wide and empty valley. " +
        "By the time they reached the old wooden bridge the night had already covered the road."

    private val shortText = "He walked home through the quiet streets."

    @Test
    fun equalSplitCounts_zipMatchesSplitterOutputs() {
        val originalPieces = SentenceSplitter.splitParagraph(fourSentenceParagraph)
        val translatedPieces = SentenceSplitter.splitParagraph(fourSentenceTranslated)
        assertEquals(4, originalPieces.size)
        assertEquals(4, translatedPieces.size)

        assertEquals(
            originalPieces.zip(translatedPieces),
            splitParagraphPair(fourSentenceParagraph, fourSentenceTranslated)
        )
    }

    @Test
    fun unequalSplitCounts_groupsFinerSideToEqualCount() {
        val originalPieces = SentenceSplitter.splitParagraph(fourSentenceParagraph)
        val translatedPieces = SentenceSplitter.splitParagraph(twoSentenceTranslated)
        assertEquals(4, originalPieces.size)
        assertEquals(2, translatedPieces.size)

        val pairs = splitParagraphPair(fourSentenceParagraph, twoSentenceTranslated)
        assertEquals(2, pairs.size)

        val groupedOriginal = pairs.map { it.first }
        val translatedSide = pairs.map { it.second }
        groupedOriginal.forEach { assertTrue(it.isNotBlank()) }
        translatedSide.forEach { assertTrue(it.isNotBlank()) }

        // Remainder (0) goes to nobody here: groups of exactly 2 sentences.
        assertEquals(
            listOf(originalPieces[0] + " " + originalPieces[1], originalPieces[2] + " " + originalPieces[3]),
            groupedOriginal
        )
        // Coarser side passes through untouched.
        assertEquals(translatedPieces, translatedSide)
        // Invariant: joining groups with " " reproduces the full splitter output.
        assertEquals(originalPieces.joinToString(" "), groupedOriginal.joinToString(" "))
        assertEquals(translatedPieces.joinToString(" "), translatedSide.joinToString(" "))
    }

    @Test
    fun shortSideNotSplit_singlePairWithWholeTexts() {
        assertTrue(shortText.length < 100)

        val pairs = splitParagraphPair(shortText, fourSentenceTranslated)
        assertEquals(1, pairs.size)
        // The short side stays byte-identical, the other side keeps all its content.
        assertEquals(shortText, pairs[0].first)
        assertEquals(
            SentenceSplitter.splitParagraph(fourSentenceTranslated).joinToString(" "),
            pairs[0].second
        )

        // Both sides short: nothing is regrouped at all.
        val shortTranslated = "Он шёл домой по тихим вечерним улицам."
        assertTrue(shortTranslated.length < 100)
        assertEquals(
            listOf(shortText to shortTranslated),
            splitParagraphPair(shortText, shortTranslated)
        )
    }

    @Test
    fun expand_withoutTranslation_splitsBodiesAndCarriesLocations() {
        val first = ReaderItem.Body(
            chapterUrl = chapterUrl,
            chapterIndex = 2,
            chapterItemPosition = 7,
            text = fourSentenceParagraph,
            location = ReaderItem.Location.FIRST
        )
        val second = ReaderItem.Body(
            chapterUrl = chapterUrl,
            chapterIndex = 2,
            chapterItemPosition = 8,
            text = secondFourSentenceParagraph,
            location = ReaderItem.Location.LAST
        )

        val output = expandParagraphPairs(listOf(first, second), startPosition = 3)

        val expectedTexts = SentenceSplitter.splitParagraph(fourSentenceParagraph) +
            SentenceSplitter.splitParagraph(secondFourSentenceParagraph)
        assertEquals(8, output.size)
        assertEquals(expectedTexts, output.filterIsInstance<ReaderItem.Body>().map { it.text })
        assertEquals((3..10).toList(), output.map { (it as ReaderItem.Position).chapterItemPosition })
        assertTrue(output.filterIsInstance<ReaderItem.Body>().all { it.textTranslated == null })
        output.forEach {
            val position = it as ReaderItem.Position
            assertEquals(chapterUrl, position.chapterUrl)
            assertEquals(2, position.chapterIndex)
        }

        assertEquals(ReaderItem.Location.FIRST, (output[0] as ReaderItem.Body).location)
        assertEquals(ReaderItem.Location.LAST, (output[output.lastIndex] as ReaderItem.Body).location)
        output.subList(1, output.lastIndex).forEach {
            assertEquals(ReaderItem.Location.MIDDLE, (it as ReaderItem.Body).location)
        }
    }

    @Test
    fun expand_withTranslationAndImage_keepsImageAndSequentialPositions() {
        val bodyFirst = ReaderItem.Body(
            chapterUrl = chapterUrl,
            chapterIndex = 0,
            chapterItemPosition = 0,
            text = fourSentenceParagraph,
            location = ReaderItem.Location.FIRST,
            textTranslated = fourSentenceTranslated
        )
        val image = ReaderItem.Image(
            chapterUrl = chapterUrl,
            chapterItemPosition = 1,
            location = ReaderItem.Location.MIDDLE,
            chapterIndex = 0,
            text = "<img src=\"images/pic.png\" yrel=\"1.45\">",
            image = ImgEntry(path = "images/pic.png", yrel = 1.45f)
        )
        val bodyLast = ReaderItem.Body(
            chapterUrl = chapterUrl,
            chapterIndex = 0,
            chapterItemPosition = 2,
            text = shortText,
            location = ReaderItem.Location.LAST
        )

        val output = expandParagraphPairs(listOf(bodyFirst, image, bodyLast), startPosition = 5)

        assertEquals(6, output.size)
        // Strictly sequential, no duplicates.
        assertEquals((5..10).toList(), output.map { (it as ReaderItem.Position).chapterItemPosition })

        // Order preserved: first body pieces, then the image, then the last body piece.
        val expectedPairs = splitParagraphPair(fourSentenceParagraph, fourSentenceTranslated)
        assertEquals(expectedPairs.map { it.first }, output.take(4).map { (it as ReaderItem.Body).text })
        assertEquals(expectedPairs.map { it.second }, output.take(4).map { (it as ReaderItem.Body).textTranslated })

        val keptImage = output[4] as ReaderItem.Image
        assertEquals(image.image, keptImage.image)
        assertEquals(image.text, keptImage.text)
        assertEquals(image.chapterUrl, keptImage.chapterUrl)
        assertEquals(image.chapterIndex, keptImage.chapterIndex)
        assertEquals(ReaderItem.Location.MIDDLE, keptImage.location)

        val tail = output[5] as ReaderItem.Body
        assertEquals(shortText, tail.text)
        assertTrue(tail.textTranslated == null)

        assertEquals(ReaderItem.Location.FIRST, (output[0] as ReaderItem.Body).location)
        assertEquals(ReaderItem.Location.LAST, tail.location)
    }

    @Test
    fun expand_emptyInput_returnsEmptyList() {
        assertTrue(expandParagraphPairs(emptyList(), startPosition = 42).isEmpty())
    }
}
