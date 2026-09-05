package my.noveldokusha.cinematic_video

import java.io.File

internal object CinematicVideoNative {
    init {
        System.loadLibrary("novela_cinematic")
    }

    external fun renderNative(
        audioPath: String,
        timelinePath: String,
        outputPath: String,
        ffmpegDirectory: String,
        encoder: String = "auto",
        preset: String = "ultrafast",
    ): Int

    fun render(
        audioFile: File,
        timelineFile: File,
        outputFile: File,
        ffmpegDirectory: File,
        encoder: String = "auto",
        preset: String = "ultrafast",
    ) {
        outputFile.parentFile?.mkdirs()
        val result = renderNative(
            audioPath = audioFile.absolutePath,
            timelinePath = timelineFile.absolutePath,
            outputPath = outputFile.absolutePath,
            ffmpegDirectory = ffmpegDirectory.absolutePath,
            encoder = encoder,
            preset = preset,
        )
        if (result != 0) {
            throw CinematicVideoException("Cinematic renderer failed with code $result")
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            throw CinematicVideoException("Cinematic renderer produced no MP4")
        }
    }
}
