package my.noveldokusha.text_to_speech

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriter(private val tempFile: File) {
    private var outputStream: FileOutputStream? = null
    private var sampleRate: Int = 0
    private var channels: Int = 0
    private var bitsPerSample: Int = 16
    private var dataSize: Long = 0
    private var headerWritten = false

    fun isValidPcmFormat(audioFormat: Int): Boolean = audioFormat == 2

    fun open(sampleRateInHz: Int, channelCount: Int) {
        if (headerWritten) {
            if (sampleRateInHz != sampleRate || channelCount != channels) {
                throw AudioFormatMismatchException("Audio format changed mid-synthesis: $sampleRateInHz/$channelCount but expected $sampleRate/$channels")
            }
            return
        }
        sampleRate = sampleRateInHz
        channels = channelCount
        outputStream = FileOutputStream(tempFile).also { it.write(placeholderHeader()) }
        headerWritten = true
    }

    fun writePcm(pcm: ByteArray) {
        check(headerWritten) { "WavWriter.open() must be called before writePcm()" }
        if (dataSize + pcm.size > MAX_WAV_DATA_SIZE) throw AudioTooLargeException("WAV data exceeds the 4GB RIFF limit")
        checkNotNull(outputStream).write(pcm)
        dataSize += pcm.size
    }

    fun finish() {
        check(headerWritten) { "WavWriter.finish() called before any audio was written" }
        checkNotNull(outputStream).flush()
        checkNotNull(outputStream).close()
        outputStream = null
        patchHeader()
    }

    fun close() {
        outputStream?.let { runCatching { it.close() } }
        outputStream = null
    }

    fun dataBytesWritten(): Long = dataSize
    fun sampleRate(): Int? = sampleRate.takeIf { it > 0 }
    fun channelCount(): Int? = channels.takeIf { it > 0 }

    private fun placeholderHeader(): ByteArray {
        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, 0x46464952); buf.putInt(4, 36); buf.putInt(8, 0x45564157); buf.putInt(12, 0x20746D66)
        buf.putInt(16, 16); buf.putShort(20, 1); buf.putShort(22, channels.toShort())
        buf.putInt(24, sampleRate); buf.putInt(28, sampleRate * channels * (bitsPerSample / 8)); buf.putShort(32, (channels * 2).toShort()); buf.putShort(34, bitsPerSample.toShort())
        buf.putInt(36, 0x61746164); buf.putInt(40, 0)
        return header
    }

    private fun patchHeader() {
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.seek(4); raf.writeInt(Integer.reverseBytes((36 + dataSize).toInt()))
            raf.seek(28); raf.writeInt(Integer.reverseBytes(sampleRate * channels * (bitsPerSample / 8)))
            raf.seek(40); raf.writeInt(Integer.reverseBytes(dataSize.toInt()))
        }
    }

    companion object { private const val MAX_WAV_DATA_SIZE: Long = 0xFFFF_FFFFL - 36 }
}

class AudioFormatMismatchException(message: String) : Exception(message)
class AudioTooLargeException(message: String) : Exception(message)
