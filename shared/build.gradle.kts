plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// The iPhone app's one framework, VisorCore: every multiplatform core module, exported to Swift. One framework, so
// the modules share one Kotlin runtime and their types match across modules.
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "VisorCore"
            isStatic = true
            export(project(":core:protocol"))
        }
    }

    sourceSets.commonMain.dependencies {
        api(project(":core:protocol"))
    }
}
