import java.util.Properties

plugins {
    id("visor.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Meta's app tokens are not in the repository: they come from local.properties, or from the CI's secrets.
val localProperties = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

fun metaToken(name: String): String =
    providers.environmentVariable(name).orNull ?: localProperties?.getProperty(name).orEmpty()

android {
    namespace = "com.macedonga.visor.core.identity"

    defaultConfig {
        buildConfigField("String", "META_HW_TOKEN", "\"${metaToken("VISOR_META_HW_TOKEN")}\"")
        buildConfigField("String", "META_AR_TOKEN", "\"${metaToken("VISOR_META_AR_TOKEN")}\"")
        buildConfigField("String", "META_FRL_TOKEN", "\"${metaToken("VISOR_META_FRL_TOKEN")}\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // api: this module's API exposes session types.
    api(project(":core:session"))

    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
}
