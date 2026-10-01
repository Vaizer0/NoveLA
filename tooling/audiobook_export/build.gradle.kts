plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "my.noveldokusha.tooling.audiobook"
}

dependencies {
    implementation(projects.core)
    // ChapterContentProvider читает оглавление и текст глав напрямую
    // из локальной БД, поэтому зависимость нужна на уровне модуля.
    implementation(projects.tooling.localDatabase)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)

    testImplementation(libs.test.junit)
}
