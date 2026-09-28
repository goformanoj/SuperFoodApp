pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle fetch a full JDK (Temurin 21, with `jpackage`) ONLY when the desktop
// installer is being built — Android Studio's bundled JDK has no jpackage. Nothing is
// downloaded for any other task (see desktop/build.gradle.kts, packagingJdk).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JarvisOS"
include(":app")
// Part I — the Windows/desktop client. Compose for Desktop; see desktop/README.md.
include(":desktop")
