plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// The iPhone app's one framework, VisorCore: the shared modules and the iPhone's own Bluetooth link, behind
// VisorClient. One framework, so the modules share one Kotlin runtime.
kotlin {
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "VisorCore"
            isStatic = true
        }
    }

    sourceSets.commonMain.dependencies {
        api(project(":core:connection"))
    }
}
