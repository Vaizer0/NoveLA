package my.noveldokusha.cinematic_video

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipInputStream

/** Extracts the ABI-specific compressed FFmpeg CLI runtime only when video export starts. */
class CinematicFfmpegAssetManager(
    private val context: Context,
) {
    fun prepare(workDir: File): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
            ?: throw CinematicVideoException("Video export is currently supported on arm64-v8a devices only")

        val runtimeRoot = File(workDir, "ffmpeg-runtime")
        val ffmpeg = File(runtimeRoot, "bin/ffmpeg")
        val runtimeArchive = "cinematic/$abi/ffmpeg-runtime.zip"

        if (!ffmpeg.isFile || ffmpeg.length() == 0L) {
            runtimeRoot.deleteRecursively()
            extractRuntimeArchive(runtimeArchive, runtimeRoot)
            createLauncher(runtimeRoot)
        }

        require(ffmpeg.isFile && ffmpeg.length() > 0L) {
            "Packaged FFmpeg CLI is missing for $abi"
        }
        ffmpeg.setExecutable(true, false)
        if (!ffmpeg.canExecute()) {
            throw CinematicVideoException("Cannot prepare bundled FFmpeg launcher")
        }
        return File(runtimeRoot, "bin")
    }

    /** Executes the packaged launcher before the renderer starts, so loader/runtime failures are explicit. */
    fun verify(ffmpegDirectory: File) {
        val launcher = File(ffmpegDirectory, "ffmpeg")
        val libraryDir = ffmpegDirectory.parentFile?.resolve("lib")
            ?: throw CinematicVideoException("FFmpeg runtime library directory is missing")
        if (!libraryDir.isDirectory) {
            throw CinematicVideoException("FFmpeg runtime library directory is missing")
        }

        val process = ProcessBuilder(launcher.absolutePath, "-hide_banner", "-version")
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

    private fun extractRuntimeArchive(assetPath: String, destinationRoot: File) {
        context.assets.open(assetPath).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.replace('\\', '/').trimStart('/')
                    if (entryName.isBlank() || entryName.contains("..")) {
                        zip.closeEntry()
                        throw CinematicVideoException("Invalid FFmpeg runtime archive entry")
                    }
                    val target = File(destinationRoot, entryName)
                    val canonicalRoot = destinationRoot.canonicalFile
                    val canonicalTarget = target.canonicalFile
                    require(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw CinematicVideoException("Invalid FFmpeg runtime archive path")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output, COPY_BUFFER) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun createLauncher(runtimeRoot: File) {
        val launcher = File(runtimeRoot, "bin/ffmpeg")
        launcher.parentFile?.mkdirs()
        launcher.writeText(
            "#!/system/bin/sh\n" +
                "HERE=\"$(CDPATH= cd -- \"$(dirname -- \"${'$'}0\")\" && pwd)\"\n" +
                "export LD_LIBRARY_PATH=\"${'$'}HERE/../lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}\"\n" +
                "exec /system/bin/linker64 \"${'$'}HERE/ffmpeg.bin\" \"${'$'}@\"\n",
            Charsets.UTF_8,
        )
        launcher.setExecutable(true, false)
    }

    private fun buildLibraryPath(libraryDir: File): String {
        val existing = System.getenv("LD_LIBRARY_PATH").orEmpty()
        return if (existing.isBlank()) libraryDir.absolutePath else libraryDir.absolutePath + File.pathSeparator + existing
    }

    companion object {
        private const val COPY_BUFFER = 256 * 1024
        private val SUPPORTED_ABIS = setOf("arm64-v8a")
    }
}
