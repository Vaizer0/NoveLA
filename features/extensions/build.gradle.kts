plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.noveldokusha.android.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "my.noveldokusha.extensions"
}

dependencies {
    implementation(projects.core)
    implementation(projects.coreui)
    implementation(projects.networking)
    implementation(projects.scraper)
    implementation(projects.strings)
    implementation(projects.tooling.localDatabase)
    implementation(projects.tooling.dexLoader)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(libs.compose.androidx.activity)
    implementation(libs.compose.androidx.lifecycle.viewmodel)
    implementation(libs.compose.androidx.runtime.livedata)
    implementation(libs.compose.material3.android)
    implementation(libs.compose.accompanist.insets)
    implementation(libs.compose.landscapist.glide)
    implementation(libs.okhttp)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // AndroidX
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Hilt
    implementation(libs.hilt.android)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Timber for logging
    implementation(libs.timber)
}
