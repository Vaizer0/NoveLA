import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class PrepareCinematicFfmpegAssetsTask : DefaultTask() {
    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()

        if (!archive.isFile || sha256(archive) != expectedSha256.get().lowercase()) {
            URI(downloadUrl.get()).toURL().openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
        }

        val actualSha256 = sha256(archive)
        require(actualSha256 == expectedSha256.get().lowercase()) {
            "FFmpeg archive checksum mismatch: expected ${expectedSha256.get()}, got $actualSha256"
        }

        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        val abi = "arm64-v8a"
        val zipExtractRoot = File(temporaryDir, "ffmpeg-zip")
        zipExtractRoot.deleteRecursively()
        zipExtractRoot.mkdirs()

        var nestedArchive: File? = null
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.substringAfterLast('/').equals("ffmpeg.tar.xz", ignoreCase = true)) {
                    nestedArchive = File(zipExtractRoot, "ffmpeg.tar.xz")
                    nestedArchive!!.outputStream().use { output ->
                        zip.getInputStream(entry).use { input -> input.copyTo(output, 256 * 1024) }
                    }
                    break
                }
            }
        }

        val tarball = requireNotNull(nestedArchive) {
            "FFmpeg release archive does not contain ffmpeg.tar.xz"
        }
        val tarExtractRoot = File(temporaryDir, "ffmpeg-tar")
        tarExtractRoot.deleteRecursively()
        tarExtractRoot.mkdirs()

        val process = ProcessBuilder(
            "tar", "-xJf", tarball.absolutePath,
            "-C", tarExtractRoot.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val tarOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to unpack ffmpeg.tar.xz (exit=$exitCode): ${tarOutput.takeLast(2000)}"
        }

        val ffmpegBinary = locate(tarExtractRoot) {
            it.isFile && it.name == "ffmpeg" && it.parentFile?.name == "bin"
        }
        require(ffmpegBinary != null) { "FFmpeg CLI binary was not found inside ffmpeg.tar.xz" }

        val sourceLibDir = locate(tarExtractRoot) { it.isDirectory && it.name == "lib64" }
            ?: locate(tarExtractRoot) { it.isDirectory && it.name == "lib" }
        require(sourceLibDir != null) { "FFmpeg shared-library directory was not found" }

        val destinationRoot = outputRoot.resolve("cinematic/$abi")
        destinationRoot.mkdirs()
        val runtimeArchive = destinationRoot.resolve("ffmpeg-runtime.zip")

        ZipOutputStream(runtimeArchive.outputStream().buffered()).use { output ->
            output.setLevel(9)
            addZipFile(output, ffmpegBinary, "bin/ffmpeg.bin")
            sourceLibDir.listFiles().orEmpty()
                .filter { file ->
                    file.isFile && (
                        file.name.endsWith(".so", ignoreCase = true) ||
                            ".so." in file.name.lowercase()
                        )
                }
                .sortedBy { it.name }
                .forEach { file -> addZipFile(output, file, "lib/${file.name}") }
        }

        logger.lifecycle(
            "Prepared compressed FFmpeg runtime for $abi (${runtimeArchive.length() / (1024 * 1024)} MiB APK asset)",
        )
    }

    private fun addZipFile(output: ZipOutputStream, file: File, entryName: String) {
        output.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
        output.closeEntry()
    }

    private fun locate(root: File, predicate: (File) -> Boolean): File? {
        if (predicate(root)) return root
        root.listFiles().orEmpty().forEach { child ->
            locate(child, predicate)?.let { return it }
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

val cinematicAssetsDir = layout.buildDirectory.dir("generated/cinematic-assets")
val ffmpegArchive = layout.buildDirectory.file("downloads/ffmpeg-android-arm64.zip")

android {
    namespace = "my.noveldokusha.cinematic_video"

    buildFeatures { prefab = true }

    defaultConfig {
        ndk {
            // The bundled cinematic FFmpeg runtime is arm64-v8a only.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-O3") }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging { jniLibs.useLegacyPackaging = false }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addStaticSourceDirectory(
            cinematicAssetsDir.get().asFile.absolutePath,
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("com.viliussutkus89.ndk.thirdparty:pango-ndk26-static:1.51.0-beta-9")
}

val prepareCinematicFfmpegAssets = tasks.register<PrepareCinematicFfmpegAssetsTask>("prepareCinematicFfmpegAssets") {
    downloadUrl.set(
        "https://github.com/rhythmcache/ffmpeg-android/releases/download/build-264/" +
            "ffmpeg-8.0-ee2eb6c-Dynamic-android-arm64-v8a.zip",
    )
    expectedSha256.set("62b9ac127b75ee73873d2a953f23f31813e763695915b07c8ac0b3fcb0b6a70d")
    archiveFile.set(ffmpegArchive)
    outputDirectory.set(cinematicAssetsDir)
}

tasks.configureEach {
    // Generated FFmpeg files are registered as Android asset sources. Every
    // task that can consume that source tree must explicitly depend on the
    // preparation task; Gradle 9.5 validates these task relationships.
    if (
        name.endsWith("LintModel", ignoreCase = true) ||
        name.contains("lintVital", ignoreCase = true) ||
        (name.contains("merge", ignoreCase = true) && name.contains("Assets", ignoreCase = true))
    ) {
        dependsOn(prepareCinematicFfmpegAssets)
    }
}
