import java.util.Properties

plugins {
    id("visor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

// Meta's app tokens are not in the repository: they come from local.properties, or from the CI's secrets.
val localProperties = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

fun metaToken(name: String): String =
    providers.environmentVariable(name).orNull ?: localProperties?.getProperty(name).orEmpty()

// The tokens as a Kotlin object, BuildConfig as Android named it, so both platforms compile them.
val buildConfig = tasks.register("buildConfig") {
    val tokens = listOf("META_HW_TOKEN", "META_AR_TOKEN", "META_FRL_TOKEN").associateWith { metaToken("VISOR_$it") }
    val out = layout.buildDirectory.dir("generated/buildConfig")
    inputs.property("tokens", tokens)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/macedonga/visor/core/identity/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        val fields = tokens.entries.joinToString("\n") { (name, value) ->
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            "    const val $name: String = \"$escaped\""
        }
        file.writeText("package com.macedonga.visor.core.identity\n\nobject BuildConfig {\n$fields\n}\n")
    }
}

kotlin {
    sourceSets.commonMain {
        kotlin.srcDir(buildConfig)
        dependencies {
            // api: this module's API exposes session types.
            api(project(":core:session"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
    sourceSets.jvmMain.dependencies {
        implementation(libs.bouncycastle)
    }
}
