plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

android {
    namespace = "my.noveldokusha.cinematic_video"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
