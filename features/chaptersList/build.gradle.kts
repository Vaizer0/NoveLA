plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.noveldokusha.android.compose)
}

android {
    namespace = "my.noveldokusha.chapterslist"
}

dependencies {
    implementation(projects.core)
    implementation(projects.coreui)
    implementation(projects.strings)
    implementation(projects.data)
    implementation(projects.scraper)
    implementation(projects.navigation)
    // VideoDownloadManager (Task 14): видео качает media3 в features:reader,
    // список глав — потребитель статусов и enqueue.
    implementation(projects.features.reader)
    implementation(projects.tooling.localDatabase)
    implementation(projects.tooling.textTranslator.domain)
    implementation(projects.tooling.applicationWorkers)
    implementation(projects.tooling.audiobookExport)
    // Наблюдение прогресса воркера аудиоэкспорта (WorkManager).
    implementation(libs.androidx.workmanager)

    implementation(projects.tooling.novelMigration)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(libs.compose.androidx.activity)
    implementation(libs.compose.material3.android)
    implementation(libs.compose.androidx.lifecycle.viewmodel)
    implementation(libs.compose.androidx.material.icons.extended)
    implementation(libs.compose.coil)
    implementation(libs.compose.lazyColumnScrollbar)

    implementation(libs.timber)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}