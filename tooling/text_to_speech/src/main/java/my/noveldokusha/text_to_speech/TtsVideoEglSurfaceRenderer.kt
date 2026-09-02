package my.noveldokusha.text_to_speech

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface

/** Deterministic bitmap-to-MediaCodec Surface renderer with explicit frame timestamps. */
internal class TtsVideoEglSurfaceRenderer(private val surface: Surface) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var textureHandle = -1

    init { initialize() }

    fun draw(bitmap: Bitmap, presentationTimeUs: Long) {
        check(bitmap.width > 0 && bitmap.height > 0) { "Cannot render an empty bitmap" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "Unable to make EGL context current" }
        GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, FULLSCREEN_VERTICES)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeUs.coerceAtLeast(0L) * 1_000L)) { "Unable to set EGL presentation timestamp" }
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Unable to submit video frame" }
        checkGlError("submit video frame")
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (texture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
            if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglReleaseThread() }
            runCatching { EGL14.eglTerminate(display) }
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        texture = 0
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to acquire EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize EGL" }
        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] == 1) { "Unable to choose EGL config" }
        val config = configs[0] ?: error("EGL config missing")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "Unable to activate EGL context" }
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        check(positionHandle >= 0 && texCoordHandle >= 0 && textureHandle >= 0) { "EGL shader handles missing" }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("initialize EGL texture")
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        check(result != 0) { "Unable to create EGL shader program" }
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            error("EGL program link failed: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create EGL shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("EGL shader compile failed: $log")
        }
        return shader
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "OpenGL error 0x${Integer.toHexString(error)} during $operation" }
    }

    private companion object {
        val FULLSCREEN_VERTICES = java.nio.ByteBuffer.allocateDirect(4 * 2 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        val TEX_COORDS = java.nio.ByteBuffer.allocateDirect(4 * 2 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0) }
        const val VERTEX_SHADER = "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}"
        const val FRAGMENT_SHADER = "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture; void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}"
    }
}
