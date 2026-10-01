plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "my.noveldokusha.tooling.audiobook"
}

dependencies {
    implementation(projects.core)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)

    testImplementation(libs.test.junit)
}
