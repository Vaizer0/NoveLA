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

    /** Executes the packaged launcher before the renderer starts, so loader/runtime failures are explicit. */
    fun verify(ffmpegDirectory: File) {
        val ffmpeg = File(ffmpegDirectory, "ffmpeg")
        val libraryDir = ffmpegDirectory.parentFile?.resolve("lib")
            ?: throw CinematicVideoException("FFmpeg runtime library directory is missing")
        if (!libraryDir.isDirectory) {
            throw CinematicVideoException("FFmpeg runtime library directory is missing")
        }

        val process = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-version")
            .directory(ffmpegDirectory.parentFile)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = buildLibraryPath(libraryDir)
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw CinematicVideoException(
                "Bundled FFmpeg could not start (exit=$exitCode): ${output.takeLast(800)}"
            )
        }
    }

    private fun buildLibraryPath(libraryDir: File): String {
        val existing = System.getenv("LD_LIBRARY_PATH").orEmpty()
        return if (existing.isBlank()) libraryDir.absolutePath else libraryDir.absolutePath + File.pathSeparator + existing
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
