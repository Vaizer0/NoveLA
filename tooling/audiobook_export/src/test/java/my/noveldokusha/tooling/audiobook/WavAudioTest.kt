package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class WavAudioTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Создаёт валидный 16-битный моно WAV заданной длительности. */
    private fun writeWav(name: String, sampleRateHz: Int, channels: Int, frames: Int): File {
        val file = temp.newFile(name)
        val dataSize = frames * channels * 2
        val silence = ByteArray(minOf(dataSize, 8192))
        file.outputStream().use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(channels)
            out.writeIntLe(sampleRateHz)
            out.writeIntLe(sampleRateHz * channels * 2)
            out.writeShortLe(channels * 2)
            out.writeShortLe(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(dataSize)
            var written = 0
            while (written < dataSize) {
                val chunk = minOf(silence.size, dataSize - written)
                out.write(silence, 0, chunk)
                written += chunk
            }
        }
        return file
    }

    @Test
    fun readSegment_readsFormatAndDuration() {
        val file = writeWav("a.wav", sampleRateHz = 8000, channels = 1, frames = 8000)
        val segment = WavAudio.readSegment(file)
        assertEquals(8000, segment.sampleRateHz)
        assertEquals(1, segment.channels)
        assertEquals(1000L, segment.durationMs())
        assertEquals(44L, segment.dataOffset)
        assertEquals(16000L, segment.dataLength)
    }

    @Test
    fun streamingWriter_concatenatesSegmentsWithoutGaps() {
        val first = writeWav("first.wav", sampleRateHz = 8000, channels = 1, frames = 8000)
        val second = writeWav("second.wav", sampleRateHz = 8000, channels = 1, frames = 4000)
        val target = temp.newFile("merged.wav")

        WavAudio.StreamingWavWriter(target, sampleRateHz = 8000, channels = 1).use { writer ->
            RandomAccessFile(first, "r").use { writer.append(WavAudio.readSegment(first), it) }
            RandomAccessFile(second, "r").use { writer.append(WavAudio.readSegment(second), it) }
            writer.finish()
        }

        val merged = WavAudio.readSegment(target)
        assertEquals(1500L, merged.durationMs())
        assertEquals(24000L, merged.dataLength)
    }

    @Test
    fun streamingWriter_fixesHeaderToCoverMergedLength() {
        val first = writeWav("one.wav", sampleRateHz = 8000, channels = 1, frames = 1000)
        val target = temp.newFile("out.wav")

        WavAudio.StreamingWavWriter(target, sampleRateHz = 8000, channels = 1).use { writer ->
            RandomAccessFile(first, "r").use { writer.append(WavAudio.readSegment(first), it) }
            writer.finish()
        }

        val bytes = target.readBytes()
        assertEquals('R', bytes[0].toInt().toChar())
        assertEquals('I', bytes[1].toInt().toChar())
        // RIFF chunk size must be 36 + dataSize, otherwise players reject the file.
        val riffSize = (bytes[4].toInt() and 0xFF) or
            ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 24)
        val dataSize = (bytes[40].toInt() and 0xFF) or
            ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or
            ((bytes[43].toInt() and 0xFF) shl 24)
        assertEquals(36 + dataSize, riffSize)
        assertEquals(2000, dataSize)
    }

    @Test
    fun streamingWriter_preservesRealSampleData() {
        val source = temp.newFile("tone.wav")
        val payload = ByteArray(4000) { (it % 251).toByte() }
        // Заголовок пишется тем же кодом, что и настоящий файл, чтобы тест
        // не зависел от ручного набора байтов.
        source.outputStream().use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(36 + payload.size)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(8000)
            out.writeIntLe(16000)
            out.writeShortLe(2)
            out.writeShortLe(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(payload.size)
            out.write(payload)
        }

        val target = temp.newFile("merged-tone.wav")
        WavAudio.StreamingWavWriter(target, sampleRateHz = 8000, channels = 1).use { writer ->
            RandomAccessFile(source, "r").use { writer.append(WavAudio.readSegment(source), it) }
            writer.finish()
        }

        val merged = target.readBytes()
        val mergedData = merged.copyOfRange(44, 44 + payload.size)
        assertTrue(payload.contentEquals(mergedData))
    }

    @Test
    fun streamingWriter_rejectsMismatchedSampleRate() {
        val wrong = writeWav("wrong.wav", sampleRateHz = 16000, channels = 1, frames = 1600)
        val target = temp.newFile("mismatch.wav")
        val failure = runCatching {
            WavAudio.StreamingWavWriter(target, sampleRateHz = 8000, channels = 1).use { writer ->
                RandomAccessFile(wrong, "r").use { writer.append(WavAudio.readSegment(wrong), it) }
            }
        }.exceptionOrNull()
        assertTrue(failure is IncompatibleAudioFormatException)
    }

    @Test
    fun streamingWriter_refusesToFinalizeEmptyFile() {
        val target = temp.newFile("empty.wav")
        val failure = runCatching {
            WavAudio.StreamingWavWriter(target, sampleRateHz = 8000, channels = 1).use { it.finish() }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun readSegment_rejectsTruncatedDataChunk() {
        // Заголовок обещает 1000 байт, реально записано 400: движок не успел
        // дописать файл. Раньше это молча обрезалось и обрыв попадал в мердж.
        val file = writeWavWithDeclaredData("truncated.wav", declared = 1000, actual = 400)
        assertTrue(WavAudio.isRiffWav(file))
        assertFalse(WavAudio.isCompleteWav(file))
        assertThrows(IOException::class.java) { WavAudio.readSegment(file) }
    }

    @Test
    fun readSegment_rejectsNonFrameAlignedData() {
        // 16-битное моно: кадр 2 байта, объявленный размер data 999 не выровнен.
        val file = writeWavWithDeclaredData("misaligned.wav", declared = 999, actual = 999)
        assertThrows(IOException::class.java) { WavAudio.readSegment(file) }
    }

    @Test
    fun isCompleteWav_trueForCompleteFalseForTruncated() {
        val complete = writeWav("complete.wav", sampleRateHz = 8000, channels = 1, frames = 100)
        assertTrue(WavAudio.isCompleteWav(complete))
        val truncated = writeWavWithDeclaredData("partial.wav", declared = 1000, actual = 400)
        assertFalse(WavAudio.isCompleteWav(truncated))
    }

    /** WAV, чей заголовок объявляет больше данных, чем реально записано. */
    private fun writeWavWithDeclaredData(name: String, declared: Int, actual: Int): File {
        val file = temp.newFile(name)
        file.outputStream().use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(36 + declared)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(8000)
            out.writeIntLe(16000)
            out.writeShortLe(2)
            out.writeShortLe(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.writeIntLe(declared)
            out.write(ByteArray(actual))
        }
        return file
    }
}

private fun java.io.OutputStream.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

private fun java.io.OutputStream.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}
