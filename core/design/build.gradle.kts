plugins {
    id("visor.android.library")
    id("visor.android.compose")
}

android {
    namespace = "com.macedonga.visor.core.design"
}

dependencies {
    // MaterialTheme, fed Visor's colours and type in Theme.kt.
    implementation(libs.androidx.compose.material3)
}
