package my.noveldokusha.features.reader.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CinematicVideoExporter(
    private val context: Context,
) {
    data class Result(val outputFile: File, val durationMs: Long)

    suspend fun export(
        wavFile: File,
        timelineFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.Default) {
        require(wavFile.isFile) { "WAV file does not exist" }
        require(timelineFile.isFile) { "Timeline JSON does not exist" }
        val timelineDurationMs = JSONObject(timelineFile.readText())
            .getJSONObject("audio").getLong("durationMs")
        require(timelineDurationMs > 0) { "Timeline contains no audio duration" }

        val tempDir = File(context.cacheDir, "cinematic-export").apply { mkdirs() }
        val videoOnly = File.createTempFile("video_", ".mp4", tempDir)
        val audioOnly = File.createTempFile("audio_", ".mp4", tempDir)
        try {
            renderVideo(timelineFile, timelineDurationMs, videoOnly) { onProgress(it * 0.82f) }
            ensureActive()
            encodeWavToAacMp4(wavFile, timelineDurationMs, audioOnly) { onProgress(0.82f + it * 0.13f) }
            ensureActive()
            muxTracks(videoOnly, audioOnly, outputFile)
            onProgress(1f)
            Result(outputFile, timelineDurationMs)
        } finally {
            videoOnly.delete()
            audioOnly.delete()
        }
    }

    private fun renderVideo(
        timelineFile: File,
        durationMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val renderer = CinematicFrameRenderer(timelineFile)
        val format = MediaFormat.createVideoFormat(
            VIDEO_MIME, CinematicFrameRenderer.WIDTH, CinematicFrameRenderer.HEIGHT
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, CinematicFrameRenderer.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        var surface: Surface? = null
        var egl: EglSurfaceRenderer? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var eosSeen = false
        val info = MediaCodec.BufferInfo()

        fun drain(waitForOutput: Boolean) {
            while (!eosSeen) {
                val index = codec.dequeueOutputBuffer(info, if (waitForOutput) 10_000L else 0L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Video output format changed twice" }
                        trackIndex = checkNotNull(muxer).addTrack(codec.outputFormat)
                        checkNotNull(muxer).start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        if (info.size > 0) {
                            check(muxerStarted) { "Video buffer arrived before output format" }
                            val buffer = codec.getOutputBuffer(index) ?: error("Missing video output buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            checkNotNull(muxer).writeSampleData(trackIndex, buffer, info)
                        }
                        eosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            egl = EglSurfaceRenderer(surface)
            codec.start()

            val totalFrames = ((durationMs * CinematicFrameRenderer.FPS) + 999L) / 1000L
            for (frameIndex in 0 until totalFrames) {
                val bitmap = renderer.frameAt(frameIndex)
                checkNotNull(egl).drawBitmap(bitmap)
                checkNotNull(egl).setPresentationTime(
                    frameIndex * 1_000_000_000L / CinematicFrameRenderer.FPS
                )
                checkNotNull(egl).swapBuffers()
                drain(waitForOutput = false)
                onProgress((frameIndex + 1).toFloat() / totalFrames.toFloat())
            }
            codec.signalEndOfInputStream()
            while (!eosSeen) drain(waitForOutput = true)
            check(muxerStarted) { "Video encoder produced no output" }
        } finally {
            runCatching { codec.stop() }
            runCatching { egl?.release() }
            runCatching { surface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec.release() }
        }
    }

    private fun encodeWavToAacMp4(
        wavFile: File,
        expectedDurationMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) {
        WavPcmReader(RandomAccessFile(wavFile, "r")).use { wav ->
            require(wav.bitsPerSample == 16) { "Only PCM16 WAV is supported" }
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, wav.sampleRate, wav.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var trackIndex = -1
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val bytesPerSecond = wav.sampleRate.toLong() * wav.channels * 2L
            var consumed = 0L
            try {
                codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: error("Missing AAC input buffer")
                            input.clear()
                            val remaining = wav.remainingDataBytes()
                            if (remaining <= 0L) {
                                codec.queueInputBuffer(inputIndex, 0, 0, consumed * 1_000_000L / bytesPerSecond,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val toRead = minOf(input.remaining(), remaining.toInt())
                                val read = wav.read(input, toRead)
                                if (read <= 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, consumed * 1_000_000L / bytesPerSecond,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val ptsUs = consumed * 1_000_000L / bytesPerSecond
                                    codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
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
                                if (buffer != null && info.size > 0 && muxerStarted &&
                                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(trackIndex, buffer, info)
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            require(kotlin.math.abs(wav.durationMs - expectedDurationMs) <= 1000L) {
                "Timeline/WAV duration mismatch: wav=${wav.durationMs}ms timeline=$expectedDurationMs"
            }
        }
    }

    private fun muxTracks(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = android.media.MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = android.media.MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = selectTrack(videoExtractor, "video/")
            val audioTrack = selectTrack(audioExtractor, "audio/")
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            copyTrack(videoExtractor, videoTrack, muxer, outVideo)
            copyTrack(audioExtractor, audioTrack, muxer, outAudio)
            muxer.stop()
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            muxer.release()
        }
    }

    private fun selectTrack(extractor: android.media.MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        error("No $prefix track found")
    }

    private fun copyTrack(extractor: android.media.MediaExtractor, track: Int, muxer: MediaMuxer, outTrack: Int) {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocateDirect(1_048_576)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
            buffer.clear()
        }
    }

    private companion object {
        const val VIDEO_MIME = "video/avc"
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val VIDEO_BITRATE = 10_000_000
        const val AUDIO_BITRATE = 128_000
    }

    private class EglSurfaceRenderer(surface: Surface) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val eglContext: EGLContext
        private val eglSurface: EGLSurface
        private val program: Int
        private val texture: Int
        private val bitmapBuffer = ByteBuffer.allocateDirect(CinematicFrameRenderer.WIDTH * CinematicFrameRenderer.HEIGHT * 4)
        private val vertexBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
            )).position(0)
        }

        init {
            check(display != EGL14.EGL_NO_DISPLAY)
            check(EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                ), 0, configs, 0, 1, num, 0
            ))
            val config = configs[0] ?: error("No EGL config")
            eglContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
            program = GlProgram.create()
            texture = GlProgram.createTexture()
        }

        fun drawBitmap(bitmap: Bitmap) {
            bitmapBuffer.position(0)
            bitmap.copyPixelsToBuffer(bitmapBuffer)
            bitmapBuffer.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                bitmap.width, bitmap.height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bitmapBuffer
            )
            GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
            GLES20.glUseProgram(program)
            GlProgram.draw(texture, program, vertexBuffer)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        }

        fun setPresentationTime(nanoseconds: Long) {
            check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds))
        }

        fun swapBuffers() = check(EGL14.eglSwapBuffers(display, eglSurface))

        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
    }

    private object GlProgram {
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

        fun create(): Int {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Could not link texture program" }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return program
        }

        fun createTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return textures[0]
        }

        fun draw(texture: Int, program: Int, vertices: java.nio.FloatBuffer) {
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            val sampler = GLES20.glGetUniformLocation(program, "uTexture")
            vertices.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(texCoord)
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Could not compile GL shader" }
            return shader
        }
    }
}

private class WavPcmReader(private val file: RandomAccessFile) : AutoCloseable {
    var sampleRate: Int = 0; private set
    var channels: Int = 0; private set
    var bitsPerSample: Int = 0; private set
    var totalDataBytes: Long = 0; private set
    var durationMs: Long = 0; private set
    private var dataStart = 0L
    private var dataEnd = 0L

    init { parse() }
    fun remainingDataBytes(): Long = dataEnd - file.filePointer
    fun read(target: ByteBuffer, count: Int): Int {
        val temp = ByteArray(minOf(count, 64 * 1024))
        val read = file.read(temp)
        if (read > 0) target.put(temp, 0, read)
        return read
    }
    override fun close() = file.close()

    private fun parse() {
        file.seek(0)
        require(readAscii(4) == "RIFF") { "Not a RIFF WAV file" }
        file.skipBytes(4)
        require(readAscii(4) == "WAVE") { "Not a WAVE file" }
        var fmtFound = false
        var dataFound = false
        while (file.filePointer + 8 <= file.length()) {
            val id = readAscii(4)
            val size = readUInt32()
            val chunkStart = file.filePointer
            when (id) {
                "fmt " -> {
                    val format = readUInt16()
                    channels = readUInt16()
                    sampleRate = readUInt32().toInt()
                    file.skipBytes(6)
                    bitsPerSample = readUInt16()
                    require(format == 1) { "Only PCM WAV is supported" }
                    require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported" }
                    fmtFound = true
                }
                "data" -> {
                    dataStart = file.filePointer
                    dataEnd = dataStart + size
                    totalDataBytes = size
                }
            }
            require(chunkStart + size + (size and 1L) <= file.length()) { "Invalid WAV chunk size" }
            file.seek(chunkStart + size + (size and 1L))
            if (fmtFound && dataFound) break
            dataFound = dataFound || id == "data"
        }
        require(fmtFound && dataFound) { "WAV fmt/data chunks are missing" }
        require(sampleRate > 0 && channels > 0) { "Invalid WAV format" }
        file.seek(dataStart)
        durationMs = totalDataBytes * 1000L / (sampleRate.toLong() * channels * 2L)
    }

    private fun readAscii(n: Int): String {
        val bytes = ByteArray(n)
        file.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }
    private fun readUInt16(): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(file.readShort()))
    private fun readUInt32(): Long = java.lang.Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()))
}
