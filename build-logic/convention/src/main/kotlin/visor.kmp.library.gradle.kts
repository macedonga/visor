import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Code both apps share: the JVM target for the Android app, the iOS targets for the iPhone app and its simulator.
kotlin {
    jvm {
        // The phone runs this code, so only the JDK 17 API, whichever JDK runs Gradle.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
    iosArm64()
    iosSimulatorArm64()
}
