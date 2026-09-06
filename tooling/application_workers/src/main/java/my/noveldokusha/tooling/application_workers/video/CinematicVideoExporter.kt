package my.noveldokusha.tooling.application_workers.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Worker-local cinematic exporter used by the existing TTS download pipeline. */
class CinematicVideoExporter(private val context: Context) {
    data class Result(val outputFile: File, val durationMs: Long)

    suspend fun export(
        wavInput: InputStream,
        timelineFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
        onSizeBytes: (Long) -> Unit = {},
    ): Result = withContext(Dispatchers.Default) {
        require(timelineFile.isFile) { "Timeline JSON does not exist" }
        val timeline = JSONObject(timelineFile.readText())
        val durationMs = timeline.getJSONObject("audio").getLong("durationMs")
        require(durationMs > 0L) { "Timeline contains no audio duration" }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val tempDir = File(context.cacheDir, "cinematic-export").apply { mkdirs() }
        val videoOnly = File.createTempFile("video_", ".mp4", tempDir)
        val audioOnly = File.createTempFile("audio_", ".mp4", tempDir)
        try {
            renderVideo(timelineFile, durationMs, videoOnly) { fraction ->
                onProgress(fraction * 0.82f)
                onSizeBytes(videoOnly.length())
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            encodeWavToAacMp4(wavInput, durationMs, audioOnly) { fraction ->
                onProgress(0.82f + fraction * 0.13f)
                onSizeBytes(videoOnly.length())
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            muxTracks(videoOnly, audioOnly, outputFile) {
                onSizeBytes(outputFile.length())
            }
            onSizeBytes(outputFile.length())
            onProgress(1f)
            Result(outputFile, durationMs)
        } finally {
            videoOnly.delete()
            audioOnly.delete()
        }
    }

    private suspend fun renderVideo(
        timelineFile: File,
        durationMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val renderer = CinematicFrameRenderer(timelineFile)
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, CinematicFrameRenderer.WIDTH, CinematicFrameRenderer.HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, CinematicFrameRenderer.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        var inputSurface: Surface? = null
        var egl: EglSurfaceRenderer? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var eosSeen = false
        val info = MediaCodec.BufferInfo()

        fun drain(timeoutUs: Long) {
            while (!eosSeen) {
                val index = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Video output format changed twice" }
                        trackIndex = requireNotNull(muxer).addTrack(codec.outputFormat)
                        requireNotNull(muxer).start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted) { "Video data arrived before output format" }
                            val buffer = requireNotNull(codec.getOutputBuffer(index)) { "Missing video output buffer" }
                            val end = info.offset + info.size
                            require(info.offset >= 0 && end <= buffer.capacity()) { "Invalid video buffer range" }
                            buffer.position(info.offset)
                            buffer.limit(end)
                            requireNotNull(muxer).writeSampleData(trackIndex, buffer, info)
                        }
                        eosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            egl = EglSurfaceRenderer(inputSurface)
            codec.start()

            val totalFrames = ((durationMs * CinematicFrameRenderer.FPS) + 999L) / 1000L
            check(totalFrames > 0L) { "No video frames to render" }
            for (frameIndex in 0 until totalFrames) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val bitmap = renderer.frameAt(frameIndex.toInt())
                requireNotNull(egl).drawBitmap(bitmap)
                requireNotNull(egl).setPresentationTime(frameIndex * 1_000_000_000L / CinematicFrameRenderer.FPS)
                requireNotNull(egl).swapBuffers()
                drain(0L)
                onProgress((frameIndex + 1).toFloat() / totalFrames.toFloat())
            }
            codec.signalEndOfInputStream()
            while (!eosSeen) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                drain(10_000L)
            }
            check(muxerStarted) { "Video encoder produced no output" }
        } finally {
            runCatching { codec.stop() }
            runCatching { egl?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec.release() }
        }
    }

    private suspend fun encodeWavToAacMp4(
        wavInput: InputStream,
        expectedDurationMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) {
        WavPcmReader(BufferedInputStream(wavInput, WAV_BUFFER_SIZE)).use { wav ->
            require(wav.bitsPerSample == 16) { "Only PCM16 WAV is supported" }
            val format = MediaFormat.createAudioFormat(AUDIO_MIME, wav.sampleRate, wav.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var trackIndex = -1
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val bytesPerSecond = wav.sampleRate.toLong() * wav.channels.toLong() * 2L
            var consumed = 0L
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                while (!outputDone) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val input = requireNotNull(codec.getInputBuffer(inputIndex)) { "Missing AAC input buffer" }
                            input.clear()
                            val remaining = wav.remainingDataBytes()
                            if (remaining <= 0L) {
                                codec.queueInputBuffer(inputIndex, 0, 0, consumed * 1_000_000L / bytesPerSecond, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val count = minOf(input.remaining().toLong(), remaining).toInt()
                                val read = wav.read(input, count)
                                if (read <= 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, consumed * 1_000_000L / bytesPerSecond, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, read, consumed * 1_000_000L / bytesPerSecond, 0)
                                    consumed += read
                                    onProgress((consumed.toDouble() / wav.totalDataBytes.coerceAtLeast(1L)).toFloat())
                                }
                            }
                        }
                    }
                    while (true) {
                        val outIndex = codec.dequeueOutputBuffer(info, 0L)
                        when {
                            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                check(!muxerStarted) { "AAC output format changed twice" }
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            outIndex >= 0 -> {
                                val buffer = codec.getOutputBuffer(outIndex)
                                if (buffer != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val end = info.offset + info.size
                                    require(info.offset >= 0 && end <= buffer.capacity()) { "Invalid AAC buffer range" }
                                    buffer.position(info.offset)
                                    buffer.limit(end)
                                    muxer.writeSampleData(trackIndex, buffer, info)
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                        }
                    }
                }
                check(muxerStarted) { "AAC encoder produced no output" }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            require(abs(wav.durationMs - expectedDurationMs) <= 1000L) {
                "Timeline/WAV duration mismatch: wav=${wav.durationMs}ms timeline=$expectedDurationMs"
            }
        }
    }

    private fun muxTracks(videoFile: File, audioFile: File, outputFile: File, onSize: () -> Unit) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = selectTrack(videoExtractor, "video/")
            val audioTrack = selectTrack(audioExtractor, "audio/")
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            copyTrack(videoExtractor, videoTrack, muxer, outVideo, onSize)
            copyTrack(audioExtractor, audioTrack, muxer, outAudio, onSize)
            muxer.stop()
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { muxer.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        error("No $prefix track found")
    }

    private fun copyTrack(extractor: MediaExtractor, track: Int, muxer: MediaMuxer, outTrack: Int, onSize: () -> Unit) {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocateDirect(1_048_576)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(outTrack, buffer, info)
            onSize()
            extractor.advance()
            buffer.clear()
        }
    }

    private companion object {
        const val VIDEO_MIME = "video/avc"
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val VIDEO_BITRATE = 10_000_000
        const val AUDIO_BITRATE = 128_000
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val WAV_BUFFER_SIZE = 128 * 1024
    }

    private class EglSurfaceRenderer(surface: Surface) {
        private val display: android.opengl.EGLDisplay
        private val eglContext: EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: GlProgram
        private val texture: Int

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL config" }
            val config = requireNotNull(configs[0]) { "No EGL config" }
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
            program = GlProgram.create()
            texture = GlProgram.createTexture()
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GL init failed" }
        }

        fun drawBitmap(bitmap: Bitmap) {
            check(bitmap.config == Bitmap.Config.ARGB_8888) { "Unsupported bitmap config: ${bitmap.config}" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Bitmap upload failed" }
            GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
            program.draw()
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GL draw failed" }
        }

        fun setPresentationTime(nanoseconds: Long) {
            check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds)) { "eglPresentationTimeANDROID failed" }
        }

        fun swapBuffers() {
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed: ${EGL14.eglGetError()}" }
        }

        fun release() {
            runCatching { program.release() }
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            runCatching { EGL14.eglDestroyContext(display, eglContext) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    private class GlProgram private constructor(
        private val program: Int,
        private val positionLocation: Int,
        private val texCoordLocation: Int,
        private val samplerLocation: Int,
        private val vertexBuffer: java.nio.FloatBuffer,
    ) {
        companion object {
            private const val VERTEX = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
            """
            private const val FRAGMENT = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
            """
            private val VERTICES = floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f,
            )

            fun create(): GlProgram {
                val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
                val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
                val program = GLES20.glCreateProgram()
                check(program != 0) { "glCreateProgram failed" }
                GLES20.glAttachShader(program, vertex)
                GLES20.glAttachShader(program, fragment)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                check(status[0] != 0) { "Could not link texture program" }

                val position = GLES20.glGetAttribLocation(program, "aPosition")
                val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
                val sampler = GLES20.glGetUniformLocation(program, "uTexture")
                check(position >= 0 && texCoord >= 0 && sampler >= 0) { "Texture shader locations missing" }

                val buffer = ByteBuffer
                    .allocateDirect(VERTICES.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(VERTICES)
                        position(0)
                    }

                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glUniform1i(sampler, 0)

                buffer.position(0)
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer)
                buffer.position(2)
                GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glEnableVertexAttribArray(texCoord)
                buffer.position(0)

                return GlProgram(program, position, texCoord, sampler, buffer)
            }

            fun createTexture(): Int {
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                check(textures[0] != 0) { "glGenTextures failed" }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                return textures[0]
            }

            private fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                check(shader != 0) { "glCreateShader failed" }
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "Could not compile GL shader" }
                return shader
            }
        }

        fun draw() {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            runCatching { GLES20.glDisableVertexAttribArray(positionLocation) }
            runCatching { GLES20.glDisableVertexAttribArray(texCoordLocation) }
            runCatching { GLES20.glDeleteProgram(program) }
        }
    }
}

private class WavPcmReader(inputStream: InputStream) : AutoCloseable {
    var sampleRate: Int = 0; private set
    var channels: Int = 0; private set
    var bitsPerSample: Int = 0; private set
    var totalDataBytes: Long = 0; private set
    var durationMs: Long = 0; private set
    private val input = inputStream
    private val buffer = ByteArray(64 * 1024)
    private var position = 0L
    private var dataStart = 0L
    private var dataEnd = 0L

    init { parse() }

    fun remainingDataBytes(): Long = (dataEnd - position).coerceAtLeast(0L)

    fun read(target: ByteBuffer, count: Int): Int {
        if (count <= 0 || position >= dataEnd) return 0
        var total = 0
        while (total < count) {
            val requested = minOf(count - total, buffer.size, (dataEnd - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val read = input.read(buffer, 0, requested)
            if (read <= 0) break
            target.put(buffer, 0, read)
            position += read
            total += read
            if (read < requested) break
        }
        return total
    }

    override fun close() = input.close()

    private fun parse() {
        require(readAscii(4) == "RIFF") { "Not a RIFF WAV file" }
        skipFully(4)
        require(readAscii(4) == "WAVE") { "Not a WAVE file" }
        var fmtFound = false
        while (true) {
            val id = readAsciiOrNull(4) ?: break
            val size = readUInt32()
            val start = position
            when (id) {
                "fmt " -> {
                    require(size >= 16L) { "Invalid WAV fmt chunk" }
                    val format = readUInt16()
                    channels = readUInt16()
                    sampleRate = readUInt32().toInt()
                    skipFully(6)
                    bitsPerSample = readUInt16()
                    require(format == 1) { "Only PCM WAV is supported" }
                    require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported" }
                    skipFully(size - 16L)
                    fmtFound = true
                }
                "data" -> {
                    require(fmtFound) { "WAV fmt chunk must precede data chunk" }
                    dataStart = start
                    dataEnd = start + size
                    totalDataBytes = size
                    require(sampleRate > 0 && channels > 0) { "Invalid WAV format" }
                    position = dataStart
                    durationMs = totalDataBytes * 1000L / (sampleRate.toLong() * channels.toLong() * 2L)
                    return
                }
                else -> skipFully(size)
            }
            if ((size and 1L) != 0L) skipFully(1)
        }
        error("WAV fmt/data chunks are missing")
    }

    private fun readAscii(n: Int): String {
        val bytes = ByteArray(n)
        readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readAsciiOrNull(n: Int): String? {
        val bytes = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(bytes, offset, n - offset)
            if (read < 0) return null
            if (read == 0) continue
            offset += read
            position += read
        }
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readUInt16(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readUInt32(): Long {
        val b0 = readUnsignedByte().toLong()
        val b1 = readUnsignedByte().toLong()
        val b2 = readUnsignedByte().toLong()
        val b3 = readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readUnsignedByte(): Int {
        val value = input.read()
        require(value >= 0) { "Unexpected end of WAV file" }
        position++
        return value
    }

    private fun readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            require(read > 0) { "Unexpected end of WAV file" }
            offset += read
            position += read
        }
    }

    private fun skipFully(count: Long) {
        require(count >= 0L) { "Invalid negative WAV skip" }
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                position += skipped
                remaining -= skipped
            } else {
                val value = input.read()
                require(value >= 0) { "Unexpected end of WAV file" }
                position++
                remaining--
            }
        }
    }
}
