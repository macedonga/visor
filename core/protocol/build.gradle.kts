plugins {
    id("visor.kmp.library")
}

// No Android dependency, on purpose. commonMain is the wire format; jvmMain holds the ciphers until iOS has its own.
