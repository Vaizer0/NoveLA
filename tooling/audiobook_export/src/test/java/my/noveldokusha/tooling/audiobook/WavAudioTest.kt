package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile

class WavAudioTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Создаёт валидный 16-битный моно WAV заданной длительности. */
    private fun writeWav(name: String, sampleRateHz: Int, channels: Int, frames: Int): java.io.File {
        val file = temp.newFile(name)
        val dataSize = frames * channels * 2
        val riffSize = 36 + dataSize
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeBytes("RIFF")
            raf.writeIntLe(riffSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLe(16)
            raf.writeShortLe(1)
            raf.writeShortLe(channels)
            raf.writeIntLe(sampleRateHz)
            raf.writeIntLe(sampleRateHz * channels * 2)
            raf.writeShortLe(channels * 2)
            raf.writeShortLe(16)
            raf.writeBytes("data")
            raf.writeIntLe(dataSize)
            repeat(dataSize) { raf.write(0.toByte()) }
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
        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x60, 0x0F, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0,
            1, 0,
            1, 0,
            0x40, 0x1F, 0, 0,
            0x80, 0x3E, 0, 0,
            2, 0,
            16, 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            0x40, 0x0F, 0, 0,
        )
        source.writeBytes(header)
        source.appendBytes(payload)

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
}

private fun RandomAccessFile.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

private fun RandomAccessFile.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}
