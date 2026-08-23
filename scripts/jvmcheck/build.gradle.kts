// Off-device compile + test gate for JARVIS OS.
//
// WHY THIS EXISTS
// ---------------
// Claude cannot build the app here: `dl.google.com` is refused by the network
// policy, so AGP, androidx and Compose cannot be resolved, and there is no
// Android SDK. For a long time that meant every Kotlin change was pushed on
// reasoning alone and the first real check was CI, twenty minutes later —
// which is how a session once burned four builds on typos.
//
// But `dl.google.com` is the ONLY thing that is blocked. Maven Central works.
// And the non-UI sources reference nothing in `ui/` and nothing in `R` — so
// they type-check against the real Android framework classes (taken from
// Robolectric's `android-all` jar, which IS on Central) and the pure test
// classes run for real.
//
// This is a gate, not a substitute for CI: the Compose layer is unverifiable
// here, and so is anything needing a device. What it does catch is the whole
// class of failure that wastes a build — a typo, a bad signature, a dangling
// reference after a refactor, a broken pure-logic test.
//
// Run:  gradle -p scripts/jvmcheck test
plugins {
    kotlin("jvm") version "2.0.21"
}

// Everything is addressed relative to the repo root, two levels up, so the
// harness can be run from anywhere.
val repoRoot: File = rootDir.parentFile.parentFile
val appMain: File = File(repoRoot, "app/src/main/java")
val appTest: File = File(repoRoot, "app/src/test/java")

// Compose-free sources under `ui/`, and the tests that cover them. Keep the two
// lists together: adding a file here that imports androidx breaks the harness
// with a resolution error rather than a clear one, so the bar is literally "does
// it import anything from androidx".
val UI_PACKAGE = "com/jarvis/os/ui/"
val PURE_UI_SOURCES = setOf(
    "OrbMath.kt",
    "UniverseMath.kt",
    // The orb's 3D maths, the per-theme specs, and the enum that keys them.
    // OrbStyle was moved out of JarvisPalette.kt purely so these three could be
    // reached from here — one `Color` import in that file was putting every orb
    // number out of range of the only gate that runs before CI, and the Orbit
    // orb shipped clipped at both edges as a direct result.
    "Orb3D.kt",
    "Orb3DSpecs.kt",
    "OrbStyle.kt",
    "BackdropStyle.kt",
    "Cosmos.kt",
)
val PURE_UI_TESTS = setOf(
    "UniverseMathTest.kt",
    "OrbitThemeTest.kt",
    "OrbFitTest.kt",
    "Orb3DTest.kt",
    "OrbMathTest.kt",
    "BackdropStyleTest.kt",
    "CosmosTest.kt",
)

fun isNonPureUi(element: FileTreeElement, keep: Set<String>): Boolean =
    !element.isDirectory && element.path.contains(UI_PACKAGE) && element.file.name !in keep

kotlin {
    compilerOptions {
        // Only JDK 21 is present in this environment. CI builds on 17; the
        // difference is invisible to everything this harness checks.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // The app is written against a real Android SDK; the stubs below are
        // deliberately minimal, so do not fail the build on warnings.
        freeCompilerArgs.add("-nowarn")
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf(appMain, File(projectDir, "stubs")))
        kotlin.exclude(
            // Pulls in ComponentActivity and the Compose entry point.
            "**/MainActivity.kt",
        )
        // Compose + androidx are not resolvable without dl.google.com, so `ui/`
        // is out — EXCEPT the handful of files in it that import no Compose at
        // all. Those are pure arithmetic that happens to serve the UI, and there
        // is no reason for the one gate that runs here to be blind to them:
        // UniverseMath decides where an endless zoom puts every shell, which is
        // exactly the kind of sign error that is free to catch here and costs a
        // twenty-minute build to catch in CI.
        //
        // A Spec rather than an include pattern, because in Gradle's pattern
        // sets an exclude always beats an include — "exclude ui/** then include
        // one file back" silently keeps excluding it.
        //
        // The `isDirectory` guard is not defensive, it is required: Gradle asks
        // the Spec about DIRECTORIES too, and excluding one prunes everything
        // beneath it. Without the guard, `ui/components` is excluded (its name is
        // not in the list) and the files inside are never even offered — which
        // looks exactly like the Spec not working.
        kotlin.exclude { isNonPureUi(it, PURE_UI_SOURCES) }
    }
    test {
        kotlin.setSrcDirs(listOf(appTest))
        kotlin.exclude(
            // Robolectric's own runtime fetches an android-all jar at TEST time
            // and needs a full SDK; these run in CI instead.
            "**/*RobolectricTest.kt",
            "**/GroqClientParseTest.kt",
            "**/GeminiClientParseTest.kt",
        )
        // Same rule as `main`: the tests for the pure files run here too.
        kotlin.exclude { isNonPureUi(it, PURE_UI_TESTS) }
    }
}

dependencies {
    // The real android.jar, compile-time only. Robolectric publishes it to
    // Maven Central, which is the whole reason this harness is possible.
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
    testCompileOnly("org.robolectric:android-all:16-robolectric-13921718")
    testRuntimeOnly("org.robolectric:android-all:16-robolectric-13921718")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // MelSpectrogramTest reads its weights blob by a repo-relative path.
    workingDir = repoRoot
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
