plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "my.noveldokusha.dexloader"
}

dependencies {
    implementation(projects.scraper)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)
}
