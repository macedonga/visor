plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.transport"
}

dependencies {
    // The Transport interface the Android pipes implement lives with the session, which iOS shares.
    api(project(":core:session"))
}
