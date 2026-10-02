package my.noveldokusha.tooling.audiobook

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Приводит файл, созданный TTS-движком, к обычному 16-битному PCM WAV.
 *
 * `TextToSpeech.synthesizeToFile` не гарантирует формат: AOSP/Google отдают
 * RIFF WAV, но часть движков (например, прошивочные на ColorOS) пишут сырой
 * PCM без заголовка или контейнер (MP3/OGG). Для склейки в один WAV нужен
 * единый формат, поэтому здесь файл либо оборачивается, либо декодируется.
 */
object AudioDecoder {

    private const val TIMEOUT_US = 20_000L
    private const val WAV_HEADER_SIZE = 44L

    /**
     * Гарантирует, что [file] — корректный 16-битный PCM WAV.
     *
     * @param fallback формат, сообщённый движком через `onBeginSynthesis`;
     *   используется, если файл оказался сырым PCM без контейнера.
     * @return true, если после вызова [file] читается как WAV.
     */
    fun ensurePcmWav(file: File, fallback: SynthFormat?): Boolean {
        if (isRiffWav(file)) return true
        logHead(file)

        val parent = file.parentFile ?: return false
        val normalised = File(parent, file.name + ".normalized.wav")
        val decoded = runCatching { decodeWithMediaCodec(file, normalised) }.getOrDefault(false)
        val wrapped = decoded || runCatching { wrapRawPcm(file, normalised, fallback) }.getOrDefault(false)

        if (!wrapped || !normalised.exists() || normalised.length() <= WAV_HEADER_SIZE) {
            normalised.delete()
            return false
        }
        file.delete()
        if (normalised.renameTo(file)) return true
        return runCatching {
            normalised.copyTo(file, overwrite = true)
            normalised.delete()
            true
        }.getOrDefault(false)
    }

    private fun isRiffWav(file: File): Boolean {
        if (!file.exists() || file.length() < 12L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                String(bytes, Charsets.US_ASCII) == "RIFF"
            }
        }.getOrDefault(false)
    }

    private fun logHead(file: File) {
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val count = minOf(16L, raf.length()).toInt()
                val bytes = ByteArray(count)
                raf.readFully(bytes)
                AudiobookExportDebug.log(
                    "TTS output is not RIFF (size=${raf.length()}), head=" +
                        bytes.joinToString(" ") { "%02X".format(it) },
                )
            }
        }
    }

    /** Декодирует контейнер (MP3/OGG/…) в PCM через штатные кодеки Android. */
    private fun decodeWithMediaCodec(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcm = File.createTempFile("novela_pcm_", ".raw", input.parentFile)
        var sampleRate = 0
        var channels = 0
        var wroteBytes = 0L
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return false

            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            RandomAccessFile(pcm, "rw").use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer == null) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && info.size > 0 && !isConfig) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                buffer.get(chunk)
                                out.write(chunk)
                                wroteBytes += chunk.size
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            // Сырой PCM из кодека бывает только 16-битным в 99% случаев;
            // остальное безопаснее не выдавать как WAV.
            if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                Timber.w("AudioDecoder: unsupported PCM encoding $pcmEncoding")
                return false
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioDecoder: MediaCodec decode failed")
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        if (wroteBytes <= 0L || sampleRate <= 0 || channels <= 0) {
            pcm.delete()
            return false
        }
        writeWavHeader(output, wroteBytes, sampleRate, channels)
        appendFile(output, pcm)
        pcm.delete()
        AudiobookExportDebug.log(
            "AudioDecoder: decoded container to ${sampleRate}Hz/${channels}ch, ${wroteBytes}B",
        )
        return true
    }

    /** Оборачивает сырой PCM без контейнера в WAV, используя формат движка. */
    private fun wrapRawPcm(input: File, output: File, fallback: SynthFormat?): Boolean {
        val sampleRate = fallback?.sampleRateHz ?: return false
        val channels = fallback.channels
        if (sampleRate <= 0 || channels <= 0) return false
        val dataBytes = input.length()
        if (dataBytes <= 0L) return false
        writeWavHeader(output, dataBytes, sampleRate, channels)
        appendFile(output, input)
        AudiobookExportDebug.log(
            "AudioDecoder: wrapped raw PCM as ${sampleRate}Hz/${channels}ch, ${dataBytes}B",
        )
        return true
    }

    private fun writeWavHeader(output: File, dataBytes: Long, sampleRate: Int, channels: Int) {
        val bitsPerSample = 16
        val bytesPerFrame = channels * (bitsPerSample / 8)
        val header = ByteArray(WAV_HEADER_SIZE.toInt())
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(((dataBytes + WAV_HEADER_SIZE - 8).coerceAtMost(0xFFFFFFFFL)).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * bytesPerFrame)
        buffer.putShort(bytesPerFrame.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        FileOutputStream(output, false).use { it.write(header) }
    }

    private fun appendFile(output: File, source: File) {
        FileOutputStream(output, true).use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
    }
}
