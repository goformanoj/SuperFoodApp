// Root build file. AGP 9 provides built-in Kotlin support, so no separate
// org.jetbrains.kotlin.android plugin is declared. AGP 9.1.0 bundles KGP 2.2.10,
// so the Compose compiler plugin is pinned to the matching 2.2.10.
//
// Part I — the desktop client (`:desktop`) is Compose Multiplatform for Desktop on
// the same Kotlin. It is a module ADDED beside `:app`; nothing about the Android
// build changes. Compose MP 1.9.x supports Kotlin 2.2.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}
