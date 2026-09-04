package my.noveldokusha.features.tts

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Device-specific TTS export benchmark. It creates isolated TTS clients and never touches reader TTS.
 */
class TtsExportDiagnosticActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "NoveLA TTS Export Diagnostic"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }
        runButton = Button(this).apply {
            text = "Run diagnostic"
            setOnClickListener { runDiagnostic() }
        }
        output = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 0)
            textIsSelectable = true
        }
        root.addView(title)
        root.addView(runButton, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun runDiagnostic() {
        runButton.isEnabled = false
        output.text = "Running three isolated TTS tests...\n\n"
        Thread {
            val report = runCatching { runAllTests() }
                .getOrElse { "Diagnostic failed: ${it.stackTraceToString()}" }
            val reportFile = File(filesDir, "tts-export-diagnostic.txt")
            runCatching { reportFile.writeText(report) }
            runOnUiThread {
                output.text = "$report\n\nSaved report: ${reportFile.absolutePath}"
                runButton.isEnabled = true
            }
        }.start()
    }

    private fun runAllTests(): String {
        val device = buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.DISPLAY}")
        }
        val initLatch = CountDownLatch(1)
        val probe = TextToSpeech(applicationContext) { initLatch.countDown() }
        if (!initLatch.await(20, TimeUnit.SECONDS)) {
            runCatching { probe.shutdown() }
            return device + "TTS initialization timed out."
        }
        val enginePackage = probe.defaultEngine.orEmpty()
        val voice = probe.voices?.firstOrNull { it.locale?.language == Locale.US.language } ?: probe.voice
        val voiceId = voice?.name.orEmpty()
        val locale = voice?.locale?.toLanguageTag().orEmpty()
        val network = voice?.isNetworkConnectionRequired ?: false
        val header = buildString {
            appendLine(device.trimEnd())
            appendLine("Engine: $enginePackage")
            appendLine("Voice: $voiceId")
            appendLine("Locale: $locale")
            appendLine("Network voice: $network")
            appendLine("Speech rate: 1.0, pitch: 1.0")
            appendLine("Test text: \"Hello world. This is a TTS export timing test for word highlight ranges.\"")
            appendLine()
        }
        runCatching { probe.shutdown() }
        val results = listOf(
            runSingleTest("synthesizeToFile -> /dev/null", voiceId, enginePackage, Mode.DEV_NULL),
            runSingleTest("synthesizeToFile -> temp WAV", voiceId, enginePackage, Mode.TEMP_FILE),
            runSingleTest("speak(volume=0)", voiceId, enginePackage, Mode.MUTED_SPEAK),
        )
        return header + results.joinToString("\n") +
            "\nInterpretation: fastest method with audio callbacks and non-zero onRangeStart count is the preferred candidate."
    }

    private enum class Mode { DEV_NULL, TEMP_FILE, MUTED_SPEAK }

    private data class TestResult(
        val name: String,
        val elapsedMs: Long,
        val audioCallbacks: Int,
        val audioBytes: Long,
        val rangeCount: Int,
        val firstRange: String,
        val lastRange: String,
        val resultCode: Int,
        val error: String?,
    ) {
        override fun toString(): String = buildString {
            appendLine("=== $name ===")
            appendLine("Elapsed: ${elapsedMs} ms")
            appendLine("onAudioAvailable callbacks: $audioCallbacks")
            appendLine("Audio bytes received: $audioBytes")
            appendLine("onRangeStart count: $rangeCount")
            appendLine("First range: $firstRange")
            appendLine("Last range: $lastRange")
            appendLine("API result: $resultCode")
            if (error != null) appendLine("Error: $error")
        }
    }

    private fun runSingleTest(name: String, voiceId: String, enginePackage: String, mode: Mode): TestResult {
        val text = "Hello world. This is a TTS export timing test for word highlight ranges."
        val audioCallbacks = AtomicInteger(0)
        var audioBytes = 0L
        var rangeCount = 0
        var firstRange = "none"
        var lastRange = "none"
        var resultCode = Int.MIN_VALUE
        var error: String? = null
        val done = CountDownLatch(1)
        val init = CountDownLatch(1)
        val tts = if (enginePackage.isBlank()) {
            TextToSpeech(applicationContext) { init.countDown() }
        } else {
            TextToSpeech(applicationContext, { init.countDown() }, enginePackage)
        }
        if (!init.await(20, TimeUnit.SECONDS)) {
            runCatching { tts.shutdown() }
            return TestResult(name, 0, 0, 0, 0, "none", "none", resultCode, "TTS initialization timed out")
        }
        val voice = tts.voices?.firstOrNull { it.name == voiceId } ?: tts.voice
        if (voice != null) tts.voice = voice
        tts.setSpeechRate(1f)
        tts.setPitch(1f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { done.countDown() }
            override fun onError(utteranceId: String?) { error = "deprecated onError"; done.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { error = "TTS error $errorCode"; done.countDown() }
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                audioCallbacks.incrementAndGet()
                synchronized(this) { audioBytes += audio.size }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                synchronized(this) {
                    rangeCount++
                    if (firstRange == "none") firstRange = "($start,$end,frame=$frame)"
                    lastRange = "($start,$end,frame=$frame)"
                }
            }
        })
        val id = "diag_${System.nanoTime()}"
        val started = System.nanoTime()
        var tempFile: File? = null
        var fd: ParcelFileDescriptor? = null
        try {
            resultCode = when (mode) {
                Mode.DEV_NULL -> {
                    if (Build.VERSION.SDK_INT < 26) {
                        error = "ParcelFileDescriptor synthesizeToFile requires API 26+"
                        TextToSpeech.ERROR
                    } else {
                        fd = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_WRITE_ONLY)
                        tts.synthesizeToFile(text, Bundle(), fd!!, id)
                    }
                }
                Mode.TEMP_FILE -> {
                    tempFile = File.createTempFile("tts_diag_", ".wav", cacheDir)
                    tts.synthesizeToFile(text, Bundle(), tempFile!!, id)
                }
                Mode.MUTED_SPEAK -> {
                    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                }
            }
            if (resultCode != TextToSpeech.SUCCESS && error == null) {
                error = "request rejected"
                done.countDown()
            }
            if (resultCode == TextToSpeech.SUCCESS && !done.await(90, TimeUnit.SECONDS)) {
                error = "timeout waiting for onDone"
            }
        } catch (t: Throwable) {
            error = t.toString()
            done.countDown()
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
            runCatching { fd?.close() }
            runCatching { tempFile?.delete() }
        }
        return TestResult(
            name = name,
            elapsedMs = max(0L, (System.nanoTime() - started) / 1_000_000L),
            audioCallbacks = audioCallbacks.get(),
            audioBytes = synchronized(audioCallbacks) { audioBytes },
            rangeCount = synchronized(audioCallbacks) { rangeCount },
            firstRange = synchronized(audioCallbacks) { firstRange },
            lastRange = synchronized(audioCallbacks) { lastRange },
            resultCode = resultCode,
            error = error,
        )
    }
}
