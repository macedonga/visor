pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "visor"

// The kit (github.com/macedonga/visor-kit), built from source while it sits next to this repo, so a change to its
// contract and Visor's side of it build together. Without it, the release on Maven Central.
if (file("../visor-kit/settings.gradle.kts").exists()) includeBuild("../visor-kit")

apply(from = "gradle/pins.settings.gradle.kts")

include(":app")

include(":core:protocol")
include(":core:transport")
include(":core:session")
include(":core:identity")
include(":core:connection")
include(":core:media")
include(":core:settings")
include(":core:assistant")
include(":core:design")

