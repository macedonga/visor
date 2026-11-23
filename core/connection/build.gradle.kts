plugins {
    id("visor.kmp.library")
}

// GlassesConnection, its state and the services it reads first.
kotlin {
    sourceSets.commonMain.dependencies {
        // api rather than implementation: this module's public API is made of the layers below it.
        api(project(":core:identity"))
    }
    sourceSets.jvmMain.dependencies {
        compileOnly(libs.org.json)
    }
}
