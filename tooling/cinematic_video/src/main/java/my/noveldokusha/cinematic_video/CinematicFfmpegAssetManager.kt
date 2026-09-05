package my.noveldokusha.cinematic_video

import android.content.Context
import android.os.Build
import java.io.File

/** Extracts the ABI-specific FFmpeg executable from packaged assets once per cache directory. */
class CinematicFfmpegAssetManager(
    private val context: Context,
) {
    fun prepare(workDir: File): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
            ?: throw CinematicVideoException("No supported FFmpeg ABI is available")
        val destination = File(workDir, "ffmpeg")
        if (!destination.isFile || destination.length() == 0L) {
            destination.parentFile?.mkdirs()
            val assetPath = "cinematic/$abi/ffmpeg"
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
        }
        if (!destination.setExecutable(true, false) && !destination.canExecute()) {
            throw CinematicVideoException("Cannot mark FFmpeg executable")
        }
        return destination
    }

    companion object {
        private const val COPY_BUFFER = 128 * 1024
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
