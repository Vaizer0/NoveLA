package my.noveldokusha.cinematic_video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Runs the exact cinematic renderer through the JNI facade. */
class CinematicVideoRenderer {
    suspend fun render(
        request: CinematicVideoRenderRequest,
        onProgress: (Float) -> Unit = {},
    ): CinematicVideoRenderResult = withContext(Dispatchers.IO) {
        validate(request)
        request.workingDirectory.mkdirs()
        onProgress(0f)

        CinematicVideoNative.render(
            audioFile = request.audioFile,
            timelineFile = request.timelineFile,
            outputFile = request.outputFile,
            ffmpegDirectory = request.workingDirectory,
        )

        onProgress(1f)
        CinematicVideoRenderResult(
            outputFile = request.outputFile,
            durationMs = parseDurationMs(request.timelineFile),
        )
    }

    private fun validate(request: CinematicVideoRenderRequest) {
        if (!request.audioFile.isFile || request.audioFile.length() <= 0L) {
            throw CinematicVideoException("Cannot render video: WAV file is missing or empty")
        }
        if (!request.timelineFile.isFile || request.timelineFile.length() <= 0L) {
            throw CinematicVideoException("Cannot render video: timeline JSON is missing or empty")
        }
    }

    private fun parseDurationMs(timelineFile: File): Long =
        runCatching { timelineFile.readText(Charsets.UTF_8) }
            .mapCatching {
                Regex("\\\"durationMs\\\"\\s*:\\s*(\\d+)")
                    .find(it)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?: 0L
            }
            .getOrDefault(0L)
}
