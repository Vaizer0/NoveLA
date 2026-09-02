plugins {
    alias(libs.plugins.noveldokusha.android.library.nohilt)
}

android {
    namespace = "my.noveldokusha.reader_visuals"
}

dependencies {
    implementation(projects.core)
    implementation(projects.coreui)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.test.junit)
    testImplementation("org.json:json:20240303")
}