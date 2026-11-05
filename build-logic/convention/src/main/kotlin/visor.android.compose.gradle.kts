import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
}
pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> { buildFeatures.compose = true }
}

dependencies {
    val bom = libs.findLibrary("androidx-compose-bom").get()
    "implementation"(platform(bom))

    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-graphics").get())
    "implementation"(libs.findLibrary("androidx-compose-foundation").get())
}
