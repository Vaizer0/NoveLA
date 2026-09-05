package my.noveldokusha.cinematic_video

import java.io.File

/** Inputs for one deterministic render using an already-generated WAV + timeline JSON. */
data class CinematicVideoRenderRequest(
    val audioFile: File,
    val timelineFile: File,
    val outputFile: File,
    val rendererExecutable: File,
    val workingDirectory: File,
)

data class CinematicVideoRenderResult(
    val outputFile: File,
    val durationMs: Long,
)

class CinematicVideoException(message: String, cause: Throwable? = null) : Exception(message, cause)
