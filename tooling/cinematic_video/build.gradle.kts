import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

abstract class PrepareCinematicFfmpegAssetsTask : DefaultTask() {
    @get:InputFiles
    abstract val sourceFiles: ConfigurableFileCollection

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
        var ffmpegCopied = false
        var librariesCopied = 0

        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                val lower = normalized.lowercase()
                val fileName = normalized.substringAfterLast('/')

                val destination = when {
                    lower.endsWith("/bin/ffmpeg") || lower == "bin/ffmpeg" -> {
                        ffmpegCopied = true
                        outputRoot.resolve("cinematic/$abi/bin/ffmpeg")
                    }
                    fileName.lowercase().endsWith(".so") || ".so." in fileName.lowercase() -> {
                        librariesCopied++
                        outputRoot.resolve("cinematic/$abi/lib/$fileName")
                    }
                    else -> null
                } ?: continue

                destination.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
                if (destination.name == "ffmpeg") destination.setExecutable(true, false)
            }
        }

        require(ffmpegCopied) { "FFmpeg CLI binary was not found in the pinned Android build archive" }
        logger.lifecycle("Prepared FFmpeg CLI runtime for $abi ($librariesCopied shared libraries)")
    }

    private fun sha256(file: java.io.File): String {
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
            cinematicAssetsDir.get().asFile.absolutePath
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
            "ffmpeg-8.0-ee2eb6c-Dynamic-android-arm64-v8a.zip"
    )
    expectedSha256.set("62b9ac127b75ee73873d2a953f23f31813e763695915b07c8ac0b3fcb0b6a70d")
    archiveFile.set(ffmpegArchive)
    outputDirectory.set(cinematicAssetsDir)
}

tasks.configureEach {
    if (name.contains("merge", ignoreCase = true) && name.contains("Assets", ignoreCase = true)) {
        dependsOn(prepareCinematicFfmpegAssets)
    }
}
