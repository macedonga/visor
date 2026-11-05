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

