package my.noveldokusha.text_to_speech

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Потоковый писатель WAV (RIFF/PCM).
 *
 * Пишет PCM-байты в [tempFile] по мере поступления из onAudioAvailable —
 * без буферизации всего файла в памяти. Заголовок (44 байта) записывается
 * с плейсхолдерами и «латается» в конце через [patchHeader], т.к. размер
 * данных становится известен только после завершения синтеза.
 *
 * Не-сиквый SAF-поток не используется напрямую: готовый tempFile копируется
 * воркером в SAF и удаляется (см. TtsAudioExportWorker).
 */
class WavWriter(
    private val tempFile: File,
) {
    private var outputStream: FileOutputStream? = null
    private var sampleRate: Int = 0
    private var channels: Int = 0
    private var bitsPerSample: Int = 16
    private var dataSize: Long = 0
    private var headerWritten = false

    /** Действительный ли PCM-формат, который подходит для записи в WAV. */
    fun isValidPcmFormat(audioFormat: Int): Boolean {
        // android.media.AudioFormat.ENCODING_PCM_16BIT
        return audioFormat == 2
    }

    /**
     * Открывает файл и пишет placeholder-заголовок. Вызывается один раз на
     * первом onBeginSynthesis, когда становятся известны sampleRate/channels.
     */
    fun open(sampleRateInHz: Int, channelCount: Int) {
        if (headerWritten) {
            if (sampleRateInHz != sampleRate || channelCount != channels) {
                throw AudioFormatMismatchException(
                    "Audio format changed mid-synthesis: $sampleRateInHz/$channelCount " +
                        "but expected $sampleRate/$channels"
                )
            }
            return
        }
        this.sampleRate = sampleRateInHz
        this.channels = channelCount
        this.outputStream = FileOutputStream(tempFile).also { stream ->
            stream.write(placeholderHeader())
        }
        headerWritten = true
    }

    /** Дописывает порцию PCM-байт. */
    fun writePcm(pcm: ByteArray) {
        if (!headerWritten) {
            throw IllegalStateException("WavWriter.open() must be called before writePcm()")
        }
        // Стандартный RIFF хранит size как unsigned 32-bit, поэтому >4GB-1 не
        // помещается. Отлавливаем до записи, чтобы не получить молча битый WAV.
        if (dataSize + pcm.size > MAX_WAV_DATA_SIZE) {
            throw AudioTooLargeException(
                "WAV data exceeds the 4GB RIFF limit (chapter too long for WAV format)"
            )
        }
        checkNotNull(outputStream).write(pcm)
        dataSize += pcm.size
    }

    /** Завершает запись: латает размеры в заголовке и закрывает поток. */
    fun finish() {
        if (!headerWritten) {
            throw IllegalStateException("WavWriter.finish() called before any audio was written")
        }
        checkNotNull(outputStream).flush()
        checkNotNull(outputStream).close()
        outputStream = null
        patchHeader()
    }

    /**
     * Аварийное закрытие на ошибке/отмене: закрывает поток, если [finish] ещё
     * не вызывался. Безопасно вызывать повторно. Заголовок не латается.
     */
    fun close() {
        if (outputStream != null) {
            runCatching { outputStream!!.close() }
            outputStream = null
        }
    }

    /** Записывает данные без финализации (для отладочных целей). */
    fun dataBytesWritten(): Long = dataSize

    /** Частота дискретизации открытого WAV (0 — пока не открыт). */
    fun sampleRate(): Int = sampleRate

    /** Число каналов открытого WAV (0 — пока не открыт). */
    fun channels(): Int = channels

    private fun placeholderHeader(): ByteArray {
        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 'R'.code.toByte())
        buf.put(1, 'I'.code.toByte())
        buf.put(2, 'F'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.putInt(4, (36 + dataSize).toInt().coerceAtLeast(36)) // riff size (patched later)
        buf.put(4 + 4, 'W'.code.toByte())
        buf.put(4 + 5, 'A'.code.toByte())
        buf.put(4 + 6, 'V'.code.toByte())
        buf.put(4 + 7, 'E'.code.toByte())
        buf.put(4 + 8, 'f'.code.toByte())
        buf.put(4 + 9, 'm'.code.toByte())
        buf.put(4 + 10, 't'.code.toByte())
        buf.put(4 + 11, ' '.code.toByte())
        buf.putInt(4 + 12, 16) // fmt chunk size
        buf.putShort(4 + 16, 1) // audio format: PCM (1)
        buf.putShort(4 + 18, channels.toShort())
        buf.putInt(4 + 20, sampleRate)
        buf.putInt(4 + 24, sampleRate * channels * (bitsPerSample / 8)) // byte rate
        buf.putShort(4 + 28, (channels * (bitsPerSample / 8)).toShort()) // block align
        buf.putShort(4 + 30, bitsPerSample.toShort())
        buf.put(4 + 32, 'd'.code.toByte())
        buf.put(4 + 33, 'a'.code.toByte())
        buf.put(4 + 34, 't'.code.toByte())
        buf.put(4 + 35, 'a'.code.toByte())
        buf.putInt(4 + 36, dataSize.toInt().coerceAtLeast(0)) // data size (patched later)
        return header
    }

    private fun patchHeader() {
        RandomAccessFile(tempFile, "rw").use { raf ->
            // RIFF chunk size = 36 + data size
            raf.seek(4)
            raf.writeInt(Integer.reverseBytes((36 + dataSize).toInt()))
            // byte rate
            raf.seek(28)
            raf.writeInt(Integer.reverseBytes(sampleRate * channels * (bitsPerSample / 8)))
            // data chunk size
            raf.seek(40)
            raf.writeInt(Integer.reverseBytes(dataSize.toInt()))
        }
    }

    companion object {
        /** Максимальный размер data-чанка для стандартного RIFF (uint32). */
        private const val MAX_WAV_DATA_SIZE: Long = 0xFFFF_FFFFL - 36
    }
}

/** Несовпадение формата аудио в ходе синтеза (сеть голосов может менять формат). */
class AudioFormatMismatchException(message: String) : Exception(message)

/** Выход за лимит размера стандартного RIFF/WAV (> ~4GB). */
class AudioTooLargeException(message: String) : Exception(message)