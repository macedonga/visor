// The catalog's pin-* versions on every configuration, build script classpaths included.
gradle.beforeProject {
    // Read at resolution, when the project has its catalog.
    val pins by lazy {
        val libs = extensions.findByType<VersionCatalogsExtension>()?.find("libs")?.orElse(null)
            ?: return@lazy emptyMap<String, String>()
        libs.libraryAliases.filter { it.startsWith("pin.") }.map { libs.findLibrary(it).get().get() }
            .associate { it.module.toString() to it.version.orEmpty() }
    }
    val pin: DependencyResolveDetails.() -> Unit = { pins[requested.module.toString()]?.let { useVersion(it) } }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(pin) }
    configurations.configureEach { resolutionStrategy.eachDependency(pin) }
}
