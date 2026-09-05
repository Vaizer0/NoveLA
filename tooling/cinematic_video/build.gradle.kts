import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class PrepareCinematicFfmpegAssetsTask : DefaultTask() {
    @get:InputFiles
    abstract val ffmpegArtifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extractExecutables() {
        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        require(ffmpegArtifacts.files.isNotEmpty()) { "ffmpeg-android AAR was not resolved" }
        var copied = 0

        ffmpegArtifacts.files.forEach { aar ->
            ZipFile(aar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith("/ffmpeg")) continue
                    val abi = Regex("(?:^|/)(arm64-v8a|armeabi-v7a|x86_64|x86)/ffmpeg$")
                        .find(entry.name)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: continue
                    val destination = outputRoot.resolve("cinematic/$abi/ffmpeg")
                    destination.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                    destination.setExecutable(true, false)
                    copied++
                }
            }
        }

        require(copied > 0) {
            "ffmpeg-android AAR does not contain ABI-specific ffmpeg executables"
        }
    }
}

plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

val ffmpegArtifact = configurations.create("ffmpegArtifact")
val cinematicAssetsDir = layout.buildDirectory.dir("generated/cinematic-assets")

android {
    namespace = "my.noveldokusha.cinematic_video"

    buildFeatures {
        prefab = true
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
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

    // Cairo/Pango/HarfBuzz are statically linked into libnovela_cinematic.
    implementation("com.viliussutkus89.ndk.thirdparty:pango-ndk26-static:1.51.0-beta-9")

    // The C++ renderer invokes an ffmpeg executable by name. Extract only the
    // ABI-specific executable from this AAR; its wrapper library is not needed.
    add(ffmpegArtifact.name, "io.github.rbaucells:ffmpeg-android:1.22")
}

val prepareCinematicFfmpegAssets = tasks.register<PrepareCinematicFfmpegAssetsTask>(
    "prepareCinematicFfmpegAssets"
) {
    ffmpegArtifacts.from(ffmpegArtifact)
    outputDirectory.set(cinematicAssetsDir)
}

tasks.configureEach {
    if (name.contains("merge", ignoreCase = true) && name.contains("Assets", ignoreCase = true)) {
        dependsOn(prepareCinematicFfmpegAssets)
    }
}
