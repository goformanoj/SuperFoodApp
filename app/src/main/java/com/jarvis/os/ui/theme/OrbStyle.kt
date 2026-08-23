package com.jarvis.os.ui.theme

/**
 * Which geometry the orb draws. Each theme has its own centrepiece, not just its
 * own colours — a molten forge core does not become a nebula by recolouring it.
 *
 * **In its own file, with no imports.** That is deliberate and load-bearing: it
 * lets [com.jarvis.os.ui.components.specFor] and everything keyed on it stay
 * free of Compose, which is what lets `scripts/jvmcheck` compile and test the orb
 * geometry off-device. It used to live in `JarvisPalette.kt` next to a `Color`
 * import, and that one import was enough to put every orb number out of reach of
 * the only gate that runs before CI.
 */
enum class OrbStyle { Reactor, Filigree, Nebula, Orbit }
