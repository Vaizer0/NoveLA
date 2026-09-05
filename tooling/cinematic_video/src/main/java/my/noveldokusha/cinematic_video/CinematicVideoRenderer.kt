package my.noveldokusha.cinematic_video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Runs the exact cinematic renderer executable. The renderer source is intentionally
 * outside this Kotlin layer; this class only owns process lifecycle and validation.
 */
class CinematicVideoRenderer(
    private val environment: Map<String, String> = emptyMap(),
) {
    suspend fun render(
        request: CinematicVideoRenderRequest,
        onProgress: (Float) -> Unit = {},
    ): CinematicVideoRenderResult = withContext(Dispatchers.IO) {
        validate(request)
        request.workingDirectory.mkdirs()
        request.outputFile.parentFile?.mkdirs()

        val command = listOf(
            request.rendererExecutable.absolutePath,
            "--audio", request.audioFile.absolutePath,
            "--timeline", request.timelineFile.absolutePath,
            "--output", request.outputFile.absolutePath,
        )

        val mergedEnvironment = System.getenv().toMutableMap().apply {
            putAll(environment)
        }

        val process = ProcessBuilder(command)
            .directory(request.workingDirectory)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(mergedEnvironment)
            }
            .start()

        var lastProgress = 0f
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                // Current renderer logs are intentionally treated as opaque. Progress
                // is monotonic and reaches 1.0 only after a verified output file exists.
                val parsed = parseProgress(line)
                if (parsed != null) {
                    lastProgress = max(lastProgress, parsed)
                    onProgress(lastProgress.coerceIn(0f, 0.99f))
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw CinematicVideoException("Cinematic renderer failed with exit code $exitCode")
        }
        if (!request.outputFile.isFile || request.outputFile.length() <= 0L) {
            throw CinematicVideoException("Cinematic renderer completed without producing an MP4")
        }

        onProgress(1f)
        CinematicVideoRenderResult(
            outputFile = request.outputFile,
            durationMs = parseDurationMs(request.timelineFile),
        )
    }

    private fun validate(request: CinematicVideoRenderRequest) {
        if (!request.rendererExecutable.isFile) {
            throw CinematicVideoException(
                "Cinematic renderer binary is missing: ${request.rendererExecutable.absolutePath}"
            )
        }
        if (!request.rendererExecutable.canExecute()) {
            throw CinematicVideoException(
                "Cinematic renderer binary is not executable: ${request.rendererExecutable.absolutePath}"
            )
        }
        if (!request.audioFile.isFile || request.audioFile.length() <= 0L) {
            throw CinematicVideoException("Cannot render video: WAV file is missing or empty")
        }
        if (!request.timelineFile.isFile || request.timelineFile.length() <= 0L) {
            throw CinematicVideoException("Cannot render video: timeline JSON is missing or empty")
        }
    }

    private fun parseProgress(line: String): Float? {
        val match = Regex("(?:progress|render_progress)\\s*[=:]\\s*(\\d+(?:\\.\\d+)?)%?", RegexOption.IGNORE_CASE)
            .find(line) ?: return null
        val raw = match.groupValues[1].toFloatOrNull() ?: return null
        return if (raw > 1f) raw / 100f else raw
    }

    private fun parseDurationMs(timelineFile: File): Long {
        val text = runCatching { timelineFile.readText(Charsets.UTF_8) }.getOrNull() ?: return 0L
        return Regex("\\\"durationMs\\\"\\s*:\\s*(\\d+)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L
    }
}
