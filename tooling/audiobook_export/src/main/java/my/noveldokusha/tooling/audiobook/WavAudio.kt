package my.noveldokusha.tooling.audiobook

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Описание PCM-сегмента, полученного от TTS. */
data class PcmSegment(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataLength: Long,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val frameCount: Long get() = if (bytesPerFrame == 0) 0L else dataLength / bytesPerFrame

    /** Фактическая длительность аудио — единственный источник правды о времени. */
    fun durationMs(): Long =
        if (sampleRateHz <= 0) 0L else frameCount * 1000L / sampleRateHz
}

/** Несовместимость сегмента с уже начатымmerged-потоком. */
class IncompatibleAudioFormatException(message: String) : IOException(message)

/**
 * Низкоуровневые операции с WAV: разбор сегмента и потоковый мердж.
 *
 * Мердж намеренно работает через [RandomAccessFile]/[InputStream] с
 * фиксированным буфером: полный объём аудиокниги в память не попадает
 * никогда, независимо от длительности.
 */
object WavAudio {

    private const val RIFF_HEADER_SIZE = 44L
    private const val COPY_BUFFER = 256 * 1024

    /**
     * Разбирает WAV-сегмент и возвращает его формат.
     *
     * Поддерживает как PCM (RIFF), так и extensible-варианты, которые
     * пишут часть TTS-движков, пропуская незнакомые под-чанки.
     */
    fun readSegment(file: File): PcmSegment {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 12) { "not a RIFF file: $file" }
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff, Charsets.US_ASCII) != "RIFF") throw IOException("missing RIFF header: $file")
            raf.skipBytes(4)
            val wave = ByteArray(4)
            raf.readFully(wave)
            if (String(wave, Charsets.US_ASCII) != "WAVE") throw IOException("missing WAVE header: $file")

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var audioFormat = 0
            var dataOffset = -1L
            var dataLength = 0L

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = ByteArray(4)
                if (!readFullyOrEof(raf, chunkId)) break
                val chunkSize = readLeInt32(raf)
                val chunkStart = raf.filePointer

                when (String(chunkId, Charsets.US_ASCII)) {
                    "fmt " -> {
                        if (chunkSize < 16) throw IOException("short fmt chunk: $file")
                        val fmt = ByteArray(chunkSize.toInt().coerceAtMost(40))
                        raf.readFully(fmt)
                        audioFormat = readLe16(fmt, 0)
                        channels = readLe16(fmt, 2)
                        sampleRate = readLeInt32(fmt, 4)
                        bitsPerSample = readLe16(fmt, 14)
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        // Размер data может превышать реальный файл, если движок
                        // не успел дописать хвост — обрезаем по длине файла.
                        dataLength = minOf(chunkSize.toLong(), raf.length() - chunkStart)
                    }
                }

                if (dataOffset >= 0 && audioFormat != 0 && channels > 0) break
                val next = chunkStart + chunkSize + (chunkSize and 1L)
                if (next <= chunkStart) break
                raf.seek(next)
            }

            if (audioFormat == 0 || channels == 0 || sampleRate == 0 || bitsPerSample == 0 || dataOffset < 0) {
                throw IOException("incomplete WAV header: $file")
            }
            if (audioFormat != 1 && audioFormat != 0xFFFE) {
                throw IOException("unsupported WAV format $audioFormat: $file")
            }
            if (dataLength <= 0L) throw IOException("WAV segment has no audio data: $file")

            return PcmSegment(
                sampleRateHz = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                dataOffset = dataOffset,
                dataLength = dataLength,
            )
        }
    }

    /** Реальная длительность WAV-файла в мс. */
    fun durationMs(file: File): Long = readSegment(file).durationMs()

    /**
     * Потоковый сборщик единого WAV-файла поверх обычного [File].
     *
     * Заголовок резервируется целиком в начале, а реальные размеры
     * проставляются в [finish] — техника, позволяющая писать WAV
     * без знания итоговой длительности заранее. Готовый файл затем
     * потоково копируется в SAF.
     */
    class StreamingWavWriter(
        private val target: File,
        private val sampleRateHz: Int,
        private val channels: Int,
        private val bitsPerSample: Int = 16,
    ) : AutoCloseable {
        private val out: RandomAccessFile = RandomAccessFile(target, "rw")
        private val header = ByteArray(RIFF_HEADER_SIZE.toInt())
        private val scratch = ByteArray(COPY_BUFFER)

        /** Суммарное число записанных PCM-байт (без заголовка). */
        var dataBytes: Long = 0L
            private set

        val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)

        val totalFrames: Long get() = if (bytesPerFrame == 0) 0L else dataBytes / bytesPerFrame

        fun durationMs(): Long = if (sampleRateHz <= 0) 0L else totalFrames * 1000L / sampleRateHz

        init {
            out.setLength(0)
            writePlaceholderHeader()
            out.write(header)
        }

        /** Дописывает PCM-данные сегмента, сверяясь с форматом потока. */
        fun append(segment: PcmSegment, source: RandomAccessFile) {
            requireCompatible(segment)
            source.seek(segment.dataOffset)
            var remaining = segment.dataLength
            while (remaining > 0) {
                val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                val read = source.read(scratch, 0, toRead)
                if (read <= 0) break
                out.write(scratch, 0, read)
                dataBytes += read
                remaining -= read
            }
        }

        /** Копирует PCM-данные сегмента напрямую в поток. */
        fun append(segment: PcmSegment, source: InputStream) {
            requireCompatible(segment)
            var remaining = segment.dataLength
            while (remaining > 0) {
                val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                val read = source.read(scratch, 0, toRead)
                if (read <= 0) break
                out.write(scratch, 0, read)
                dataBytes += read
                remaining -= read
            }
        }

        private fun requireCompatible(segment: PcmSegment) {
            if (segment.sampleRateHz != sampleRateHz ||
                segment.channels != channels ||
                segment.bitsPerSample != bitsPerSample
            ) {
                throw IncompatibleAudioFormatException(
                    "segment ${segment.sampleRateHz}Hz/${segment.channels}ch/${segment.bitsPerSample}bit " +
                        "cannot be appended to ${sampleRateHz}Hz/${channels}ch/${bitsPerSample}bit stream",
                )
            }
        }

        /** Проставляет итоговые размеры в заголовке и закрывает файл. */
        fun finish() {
            if (dataBytes <= 0L) throw IOException("refusing to finalize an empty WAV")
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(4, minOf(dataBytes + RIFF_HEADER_SIZE - 8, 0xFFFFFFFFL).toInt())
            buffer.putInt(40, minOf(dataBytes, 0xFFFFFFFFL).toInt())
            out.seek(0)
            out.write(header)
            out.fd.sync()
            out.seek(out.length())
        }

        private fun writePlaceholderHeader() {
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(0)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16)
            buffer.putShort(1)
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRateHz)
            buffer.putInt(sampleRateHz * bytesPerFrame)
            buffer.putShort(bytesPerFrame.toShort())
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(0)
        }

        override fun close() {
            runCatching { out.close() }
        }
    }

    /**
     * Пишет WAV целиком, когда итоговая длительность уже известна.
     *
     * Используется для копирования в SAF: там размер data известен заранее,
     * поэтому заголовок пишется сразу и поток остаётся append-only.
     */
    fun writeCompleteWav(
        output: OutputStream,
        totalPcmBytes: Long,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int = 16,
        copy: (OutputStream) -> Unit,
    ) {
        val bytesPerFrame = channels * (bitsPerSample / 8)
        val header = ByteArray(RIFF_HEADER_SIZE.toInt())
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(minOf(totalPcmBytes + RIFF_HEADER_SIZE - 8, 0xFFFFFFFFL).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRateHz)
        buffer.putInt(sampleRateHz * bytesPerFrame)
        buffer.putShort(bytesPerFrame.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(minOf(totalPcmBytes, 0xFFFFFFFFL).toInt())
        output.write(header)
        copy(output)
    }

    private fun readFullyOrEof(raf: RandomAccessFile, buffer: ByteArray): Boolean =
        try {
            raf.readFully(buffer)
            true
        } catch (e: EOFException) {
            false
        }

    private fun readLe16(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt32(buffer: ByteArray, offset: Int): Int {
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF
        val b3 = buffer[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLeInt32(raf: RandomAccessFile): Long =
        (raf.read() and 0xFF) or
            ((raf.read() and 0xFF) shl 8) or
            ((raf.read() and 0xFF) shl 16) or
            ((raf.read() and 0xFF).toLong() shl 24).let { (it and 0xFFFFFFFFL) }
}
