plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

android {
    namespace = "my.noveldokusha.video_export"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.tooling.textToSpeech)
    implementation(projects.tooling.algorithms)
    implementation(projects.tooling.readerVisuals)
    implementation(projects.core)
    implementation(projects.coreui)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.test.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    // Robolectric-рендер QA-кадров пишет PNG сюда (артефакт CI).
    environment(
        "QA_FRAMES_DIR",
        layout.buildDirectory.dir("outputs/qa_frames").get().asFile.absolutePath
    )
    systemProperty("videoExport.highlightDebug", "1")
}
