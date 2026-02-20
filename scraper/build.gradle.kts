plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.noveldokusha.android.compose)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "my.noveldokusha.scraper"
}

dependencies {
    implementation(projects.strings)
    implementation(projects.core)
    implementation(projects.coreui)
    implementation(projects.networking)
    implementation(libs.compose.material3.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.timber)
    implementation(libs.compose.androidx.material.icons.extended)
    androidTestImplementation(libs.test.androidx.espresso.core)
}