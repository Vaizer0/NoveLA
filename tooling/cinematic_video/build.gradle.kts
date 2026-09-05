import java.util.zip.ZipFile

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

    sourceSets["main"].assets.srcDir(cinematicAssetsDir)

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Cairo/Pango/HarfBuzz are statically linked into libnovela_cinematic.
    implementation("com.viliussutkus89.ndk.thirdparty:pango-ndk26-static:1.51.0-beta-9")

    // The C++ renderer invokes an ffmpeg executable by name. Extract only the
    // ABI-specific executable from this AAR into generated assets; the wrapper
    // classes/native libraries in the AAR are deliberately not added transitively.
    add(ffmpegArtifact.name, "io.github.rbaucells:ffmpeg-android:1.22")
}

val prepareCinematicFfmpegAssets = tasks.register("prepareCinematicFfmpegAssets") {
    inputs.files(ffmpegArtifact)
    outputs.dir(cinematicAssetsDir)

    doLast {
        val outputRoot = cinematicAssetsDir.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        val artifacts = ffmpegArtifact.resolve()
        require(artifacts.isNotEmpty()) { "ffmpeg-android AAR was not resolved" }
        var copied = 0

        artifacts.forEach { aar ->
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
                    copied++
                }
            }
        }

        require(copied > 0) {
            "ffmpeg-android AAR does not contain ABI-specific ffmpeg executables"
        }
    }
}

tasks.configureEach {
    if (name.contains("merge", ignoreCase = true) && name.contains("Assets", ignoreCase = true)) {
        dependsOn(prepareCinematicFfmpegAssets)
    }
}
