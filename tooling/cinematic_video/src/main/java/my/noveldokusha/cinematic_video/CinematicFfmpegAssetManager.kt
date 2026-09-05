package my.noveldokusha.cinematic_video

import android.content.Context
import android.os.Build
import java.io.File

/** Extracts the ABI-specific FFmpeg CLI and its shared libraries from packaged assets. */
class CinematicFfmpegAssetManager(
    private val context: Context,
) {
    fun prepare(workDir: File): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
            ?: throw CinematicVideoException("Video export is currently supported on arm64-v8a devices only")

        val runtimeRoot = File(workDir, "ffmpeg-runtime")
        val ffmpeg = File(runtimeRoot, "bin/ffmpeg")

        if (!ffmpeg.isFile || ffmpeg.length() == 0L) {
            runtimeRoot.deleteRecursively()
            copyAssetTree("cinematic/$abi", runtimeRoot)
        }

        require(ffmpeg.isFile && ffmpeg.length() > 0L) {
            "Packaged FFmpeg CLI is missing for $abi"
        }
        ffmpeg.setExecutable(true, false)
        if (!ffmpeg.canExecute()) {
            throw CinematicVideoException("Cannot execute bundled FFmpeg")
        }
        return File(runtimeRoot, "bin")
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            return
        }

        destination.mkdirs()
        entries.forEach { name ->
            copyAssetTree("$assetPath/$name", File(destination, name))
        }
    }

    companion object {
        private const val COPY_BUFFER = 256 * 1024
        private val SUPPORTED_ABIS = setOf("arm64-v8a")
    }
}
