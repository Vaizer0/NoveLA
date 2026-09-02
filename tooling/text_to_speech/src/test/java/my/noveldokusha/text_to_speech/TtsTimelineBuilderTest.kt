package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Тесты построения временной шкалы синхронизации (TtsTimelineBuilder).
 *
 * Воспроизводят ровно ту последовательность вызовов, которой пользуется
 * [TtsAudioExporter]: абзац → slice → onBeginSynthesis/onAudioAvailable/onRangeStart.
 */
class TtsTimelineBuilderTest {

    // ── Помощник: прогоняет абзац с кусками так же, как экспортёр ─────────────

    private data class Slice(
        val text: String,
        val sampleRate: Int,
        val audioBytes: Int,
        val ranges: List<Triple<Int, Int, Int>>, // (start, end, frame)
    )

    private data class Para(val slices: List<Slice>)

    private fun drive(
        novelTitle: String = "Novel",
        chapterTitle: String = "Chapter 1",
        chapterIndex: Int = 0,
        source: String = "ORIGINAL",
        audioSampleRate: Int = 48000,
        audioChannels: Int = 1,
        audioDurationMs: Int,
        paragraphs: List<Para>,
    ): TtsTimeline {
        val b = TtsTimelineBuilder()
        b.beginChapter(novelTitle, chapterTitle, chapterIndex, source)
        paragraphs.forEach { para ->
            b.beginParagraph()
            para.slices.forEach { slice ->
                b.registerSlice(slice.text)
                b.setSliceFormat(slice.sampleRate, audioChannels)
                b.onAudioAvailable(slice.audioBytes)
                slice.ranges.forEach { (start, end, frame) ->
                    b.onRangeStart(start, end, frame)
                }
            }
            b.endParagraph()
        }
        return b.build(
            audioFileName = "1 - Chapter 1.wav",
            audioSampleRate = audioSampleRate,
            audioChannels = audioChannels,
            audioDurationMs = audioDurationMs,
        )
    }

    // 1. Один абзац / один slice.
    @Test
    fun `one paragraph one slice`() {
        val t = drive(
            audioDurationMs = 800,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "Hello world",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2, // 1000ms mono 16-bit @48k
                            ranges = listOf(
                                Triple(0, 5, 0),       // "Hello" @0
                                Triple(6, 11, 24000),  // "world" @500ms
                            ),
                        )
                    )
                )
            ),
        )
        assertEquals("Hello world", t.text.preparedText)
        assertEquals(11, t.text.characterCount)
        assertEquals(1, t.paragraphs.size)
        val p = t.paragraphs[0]
        assertEquals(0, p.startChar)
        assertEquals(11, p.endChar)
        assertEquals(0, p.startMs)
        assertEquals(800, p.endMs)
        assertEquals(2, p.ranges.size)
        assertEquals("Hello", p.ranges[0].text)
        assertEquals(0, p.ranges[0].startChar)
        assertEquals(5, p.ranges[0].endChar)
        assertEquals(0, p.ranges[0].startMs)
        assertEquals(500, p.ranges[0].endMs)
        assertEquals(0, p.ranges[0].frameStart)
        // frameEnd — следующий range в том же куске.
        assertEquals(24000, p.ranges[0].frameEnd)
        assertEquals("world", p.ranges[1].text)
        assertEquals(6, p.ranges[1].startChar)
        assertEquals(11, p.ranges[1].endChar)
        assertEquals(500, p.ranges[1].startMs)
        // Последний range оканчивается на длительности аудио.
        assertEquals(800, p.ranges[1].endMs)
        assertEquals(24000, p.ranges[1].frameStart)
        assertNull(p.ranges[1].frameEnd)
    }

    // 2. Один абзац / несколько slices (граница между кусками).
    @Test
    fun `one paragraph split over multiple slices`() {
        val t = drive(
            audioDurationMs = 2000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "First part. ",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2, // 1000ms
                            ranges = listOf(Triple(0, 5, 0)),   // "First"
                        ),
                        Slice(
                            text = "Second part",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2, // 1000ms
                            ranges = listOf(Triple(0, 6, 0)),   // "Second" @1000ms
                        ),
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        assertEquals("First part. Second part", p.text)
        assertEquals(2, p.ranges.size)
        // Первый range в [0, firstSlice.length).
        assertEquals("First", p.ranges[0].text)
        assertEquals(0, p.ranges[0].startMs)
        // Второй range начинается со старта второго куска (1000ms, т.к. первый кусок 1000ms).
        assertEquals(1000, p.ranges[1].startMs)
        assertEquals("Second", p.ranges[1].text)
        // "First part. " = 12 chars → "Second" начинается с char 12.
        assertEquals(12, p.ranges[1].startChar)
        assertEquals(18, p.ranges[1].endChar)
        // Непрерывность во времени между кусками.
        assertTrue(p.ranges[1].startMs >= p.ranges[0].endMs)
        assertEquals(2000, p.endMs)
    }

    // 3. Несколько абзацев.
    @Test
    fun `multiple paragraphs keep ordering and offsets`() {
        val t = drive(
            audioDurationMs = 3000,
            paragraphs = listOf(
                Para(listOf(Slice("One", 48000, 48000 * 2, listOf(Triple(0, 3, 0))))),
                Para(listOf(Slice("Two", 48000, 48000 * 2, listOf(Triple(0, 3, 0))))),
                Para(listOf(Slice("Three", 48000, 48000 * 2, listOf(Triple(0, 5, 0))))),
            ),
        )
        // preparedText = "One\n\nTwo\n\nThree"
        assertEquals("One\n\nTwo\n\nThree", t.text.preparedText)
        assertEquals(3, t.paragraphs.size)
        // Параграф 2 начинается после "One\n\n" = 5.
        assertEquals(5, t.paragraphs[1].startChar)
        assertEquals(8, t.paragraphs[1].endChar)
        assertEquals("Two", t.paragraphs[1].text)
        assertEquals("Two", t.paragraphs[1].ranges[0].text)
        // Параграф 3 после "One\n\nTwo\n\n" = 10.
        assertEquals(10, t.paragraphs[2].startChar)
        assertEquals("Three", t.paragraphs[2].ranges[0].text)
    }

    // 4. Конверсия смещений: native start/end смещены относительно куска.
    @Test
    fun `native range offsets are slice relative and mapped to absolute chapter`() {
        // Второй кусок начинается со смещением 13 в абзаце (абзац 0 → смещение 0 в preparedText).
        // Native range (5,11) внутри второго куска относителен куска → абсолютное (13+5, 13+11).
        val t = drive(
            audioDurationMs = 2000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice("Hello world. ", 48000, 48000 * 2, listOf()),
                        Slice("Next phrase", 48000, 48000 * 2, listOf(Triple(5, 11, 0))),
                    )
                )
            ),
        )
        val r = t.paragraphs[0].ranges[0]
        assertEquals(18, r.startChar)
        assertEquals(24, r.endChar)
        assertEquals("phrase", r.text)
        assertEquals("phrase", t.text.preparedText.substring(18, 24))
    }

    // 5. Frame → миллисекунды.
    @Test
    fun `frame to ms conversion at 48kHz`() {
        val t = drive(
            audioDurationMs = 2000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "abc def",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            ranges = listOf(
                                Triple(0, 3, 0),
                                Triple(4, 7, 48000), // ровно 1000ms
                            ),
                        )
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        assertEquals(0, p.ranges[0].startMs)
        assertEquals(1000, p.ranges[1].startMs)
    }

    // 6. Разные частоты дискретизации по кускам.
    @Test
    fun `different sample rates across slices`() {
        val t = drive(
            audioDurationMs = 3000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice("A", 48000, 48000 * 2 /*1000ms*/, listOf(Triple(0, 1, 0))),
                        Slice("B", 24000, 24000 * 2 /*1000ms*/, listOf(Triple(0, 1, 12000))),
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        // Второй кусок начинается на 1000ms; frame=12000 при 24к → 500ms → start 1500.
        assertEquals(1500, p.ranges[1].startMs)
    }

    // 7. Пунктуация внутри/вокруг range.
    @Test
    fun `punctuation in ranges`() {
        val t = drive(
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "Hello, world!",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            ranges = listOf(
                                Triple(0, 7, 0),    // "Hello, " включает запятую+пробел
                                Triple(7, 13, 0),   // "world!"
                            ),
                        )
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        assertEquals("Hello, ", p.ranges[0].text)
        assertEquals("world!", p.ranges[1].text)
    }

    // 8. Unicode-текст (char offset, не byte).
    @Test
    fun `unicode text uses char offsets`() {
        val t = drive(
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "Здравствуй мир",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            ranges = listOf(
                                Triple(0, 10, 0),   // "Здравствуй" (10 кириллических букв)
                                Triple(11, 14, 0),  // "мир"
                            ),
                        )
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        assertEquals("Здравствуй", p.ranges[0].text)
        assertEquals("мир", p.ranges[1].text)
        // Проверяем, что тексты совпадают с подстрокой preparedText по char-индексам.
        assertEquals(t.text.preparedText.substring(p.ranges[0].startChar, p.ranges[0].endChar), p.ranges[0].text)
        assertTrue(p.ranges[0].endChar > p.ranges[0].startChar)
    }

    // 9. Диапазоны вокруг пробелов.
    @Test
    fun `ranges around spaces`() {
        val t = drive(
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "a  b", // двойной пробел
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            ranges = listOf(
                                Triple(0, 1, 0),
                                Triple(3, 4, 0), // "b"
                            ),
                        )
                    )
                )
            ),
        )
        assertEquals("a", t.paragraphs[0].ranges[0].text)
        assertEquals("b", t.paragraphs[0].ranges[1].text)
    }

    // 10. Пустая/отсутствующая коллбэк-информация (без ranges).
    @Test
    fun `empty ranges are handled safely`() {
        val t = drive(
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice("Some text without ranges", 48000, 48000 * 2, listOf())
                    )
                )
            ),
        )
        assertEquals(1, t.paragraphs.size)
        assertTrue(t.paragraphs[0].ranges.isEmpty())
        assertEquals(0, t.paragraphs[0].startMs)
        assertEquals(0, t.paragraphs[0].endMs)
    }

    // 11. Длинный абзац / границы кусков — времена монотонны и не сбрасываются.
    @Test
    fun `long paragraph monotonic timestamps across many slices`() {
        // 100 кусков по 1000ms → длительность 100000ms.
        val slices = (0 until 100).map { i ->
            Slice(
                text = "w$i ", // уникальный токен
                sampleRate = 48000,
                audioBytes = 48000 * 2,
                ranges = listOf(Triple(0, 1 + i.toString().length, 0)), // frame 0
            )
        }
        val t = drive(
            audioDurationMs = 100000,
            paragraphs = listOf(Para(slices)),
        )
        val p = t.paragraphs[0]
        // Каждый следующий range начинается со старта следующего куска (сумма предыдущих длительностей).
        var prevMs = -1
        p.ranges.forEachIndexed { index, r ->
            assertTrue("monotonic at $index", r.startMs >= prevMs)
            prevMs = r.startMs
            assertEquals(index * 1000, r.startMs)
        }
    }

    // 12. Монотонность внутри абзаца.
    @Test
    fun `monotonic startMs within paragraph`() {
        val t = drive(
            audioDurationMs = 3000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "one two three",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            ranges = listOf(
                                Triple(0, 3, 0),
                                Triple(4, 7, 48000),
                                Triple(8, 13, 96000),
                            ),
                        )
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        assertEquals(0, p.ranges[0].startMs)
        assertEquals(1000, p.ranges[1].startMs)
        assertEquals(2000, p.ranges[2].startMs)
    }

    // 13. Параграф-тайминг от детей.
    @Test
    fun `paragraph timing derived from child ranges`() {
        val t = drive(
            audioDurationMs = 2500,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "hello",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            ranges = listOf(Triple(0, 5, 0)),
                        )
                    )
                )
            ),
        )
        assertEquals(0, t.paragraphs[0].startMs)
        // Самый последний дочерний range заканчивается на длительности аудио.
        assertEquals(2500, t.paragraphs[0].endMs)
    }

    // 14. JSON round-trip.
    @Test
    fun `json round trip`() {
        val t = drive(
            novelTitle = "My Novel",
            chapterTitle = "Chapter 5",
            chapterIndex = 4,
            source = "TRANSLATED",
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(listOf(Slice("Hello", 48000, 48000 * 2, listOf(Triple(0, 5, 0)))))
            ),
        )
        val builder = TtsTimelineBuilder()
        val json = builder.toJson(t)
        val back = builder.fromJson(json)
        assertEquals(t, back)
    }

    // 15. Детерминированный JSON.
    @Test
    fun `json is deterministic`() {
        val t = drive(
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(listOf(Slice("Hello", 48000, 48000 * 2, listOf(Triple(0, 5, 0)))))
            ),
        )
        val b = TtsTimelineBuilder()
        assertEquals(b.toJson(t), b.toJson(t))
        // Поля схемы присутствуют.
        val json = b.toJson(t)
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"preparedText\""))
        assertTrue(json.contains("\"paragraphs\""))
        assertTrue(json.contains("\"ranges\""))
        assertTrue(json.contains("\"frameStart\""))
    }

    // Ошибка: пустая глава без единого абзаца.
    @Test
    fun `empty chapter fails`() {
        val b = TtsTimelineBuilder()
        b.beginChapter("N", "C", 0, "ORIGINAL")
        // Не вызываем beginParagraph — глава без текста.
        try {
            b.build("a.wav", 48000, 1, 100)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // ok
        }
    }

    // Ошибка: абзац с открытым абзацем.
    @Test
    fun `build with open paragraph fails`() {
        val b = TtsTimelineBuilder()
        b.beginChapter("N", "C", 0, "ORIGINAL")
        b.beginParagraph()
        b.registerSlice("hello")
        try {
            b.build("a.wav", 48000, 1, 100)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // ok
        }
    }

    // 18. «Грязные» native-диапазоны (пустые, вырожденные, выходящие за пределы
    // текста/аудио) НЕ должны валить экспорт: они отбрасываются/клампятся.
    @Test
    fun `messy native ranges are sanitized not fatal`() {
        val t = drive(
            audioDurationMs = 1000,
            paragraphs = listOf(
                Para(
                    listOf(
                        Slice(
                            text = "hello world",
                            sampleRate = 48000,
                            audioBytes = 48000 * 2,
                            // end>start (пусто),end за пределы текста, start>end (вырожден),
                            // startMs вне аудио (frame велик) — всё должно быть безопасно.
                            ranges = listOf(
                                Triple(0, 0, 0),   // пустой -> отбрасывается
                                Triple(5, 3, 0),   // reversed -> отбрасывается
                                Triple(0, 99, 0),  // выходит за пределы -> клампится
                                Triple(6, 11, 50000), // 50000 frame вне аудио -> клампится
                            ),
                        )
                    )
                )
            ),
        )
        val p = t.paragraphs[0]
        // Хотя бы один валидный range сохранился, build не бросил исключение.
        assertTrue(p.ranges.isNotEmpty())
        p.ranges.forEach { r ->
            assertTrue(r.endChar > r.startChar)
            assertTrue(r.endMs >= r.startMs)
            assertTrue(r.startMs >= 0)
        }
    }
}
