plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.media"
}

dependencies {
    api(project(":core:connection"))
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
}
