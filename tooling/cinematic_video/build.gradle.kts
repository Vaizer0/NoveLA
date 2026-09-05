plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

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

dependencies {
    implementation(libs.androidx.core.ktx)

    // NDK-26 static packages are published with Prefab metadata and are intended
    // to be consumed directly by Android/AGP builds.
    implementation("com.viliussutkus89.ndk.thirdparty:pango-ndk26-static:1.51.0-beta-9")
}
