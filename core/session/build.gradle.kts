plugins {
    id("visor.kmp.library")
}

// The link: transport interface, handshake, DataX mux, reconnects. The Android pipes are in :core:transport.
kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:protocol"))
        api(libs.kotlinx.coroutines.core)
    }
}
