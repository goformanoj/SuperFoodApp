// Root build file. AGP 9 provides built-in Kotlin support, so no separate
// org.jetbrains.kotlin.android plugin is declared. AGP 9.1.0 bundles KGP 2.2.10,
// so the Compose compiler plugin is pinned to the matching 2.2.10.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
