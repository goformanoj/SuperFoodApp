plugins {
    id("com.android.application")
    // Compose compiler Gradle plugin (Kotlin 2.x). Version comes from the root build file.
    id("org.jetbrains.kotlin.plugin.compose")
}

// AI keys are provided at build time via Gradle properties or environment
// variables (e.g. GitHub Actions secrets). They are NEVER committed; empty when unset.
val geminiApiKey: String = (project.findProperty("GEMINI_API_KEY") as String?)
    ?: System.getenv("GEMINI_API_KEY")
    ?: ""
val groqApiKey: String = (project.findProperty("GROQ_API_KEY") as String?)
    ?: System.getenv("GROQ_API_KEY")
    ?: ""

android {
    namespace = "com.jarvis.os"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.os"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
    }

    signingConfigs {
        // A fixed debug key committed to the repo so every CI build shares one
        // signature. Without this, each throwaway CI runner generates its own
        // debug key and Android refuses to install the update ("App not
        // installed"). This is the standard Android debug key/password — it is
        // not a release key and guards nothing secret.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // TFLite models are memory-mapped at runtime, so they must be stored
        // uncompressed in the APK — otherwise the interpreter cannot load them.
        noCompress += "tflite"
    }

    lint {
        // Report-only for now: lint runs in CI and uploads its findings, but does
        // not fail the build. Promote to gating once the existing warnings are
        // triaged, so a real regression can't hide behind pre-existing noise.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // Lets JVM unit tests read resources and use Android stubs where needed
            // (required once Robolectric-backed tests are added).
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    // The splash screen. Android 12+ shows one whether an app asks or not, built
    // from the launcher icon over the window background — this library is how the
    // colour, the icon and the exit are chosen rather than inherited, and it
    // backports the same behaviour to the older versions this app still supports.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // On-device wake word. The embedding + wake-word models (bundled, key-free) run
    // via TensorFlow Lite; the melspectrogram step runs in pure Kotlin (see below).
    // Lets JARVIS be summoned by voice with the heavy speech recogniser switched
    // off, and (via the hotword service) from any app.
    //
    // Only the two FIXED-shape models are on TFLite now, so any runtime loads them —
    // the on-device `CONV_2D ... BytesRequired overflowed` crash was NEVER these two.
    // It was the melspectrogram model's DYNAMIC-length input, which overflowed a
    // CONV_2D at allocate time on EVERY Android runtime we tried — tensorflow-lite
    // 2.16.1 and 2.17.0 AND LiteRT 1.0.0 and 2.1.0 (each caught on the CI emulator,
    // no device). Only the Python `ai-edge-litert` runtime infers that graph's shapes
    // correctly. So the melspectrogram was reimplemented in Kotlin (`MelSpectrogram`,
    // using the model's own extracted weights, verified against the model in Python),
    // and the runtime for the remaining fixed-shape models is the standard,
    // known-good `tensorflow-lite` — no per-device workaround, no dynamic-shape path.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Hosts an empty Activity for the Compose UI tests' createComposeRule (version
    // comes from the Compose BOM, since debugImplementation extends implementation).
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Plain JVM unit tests for the pure-Kotlin logic (marker parsers, session
    // state machine). No device or emulator needed — these run in CI on every push.
    testImplementation("junit:junit:4.13.2")

    // Robolectric runs Android-framework code (SharedPreferences, PackageManager,
    // org.json, Intents) on the JVM, so app-resolution, preferences and the JSON
    // stores can be tested in CI without a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    // Instrumented tests that need the REAL Android runtime (the openWakeWord model
    // load) or the real Compose UI. Run on an emulator in a separate CI job.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // NOTE: there is deliberately NO kotlin-stdlib entry here, and adding one does
    // not work. The E2E fixture Activity runs in the TEST process
    // (com.jarvis.os.test), whose dex path is the test APK alone, and Kotlin emits
    // calls into kotlin.jvm.internal.Intrinsics for its null checks — so a Kotlin
    // fixture died the instant its click handler ran:
    //   NoClassDefFoundError: Failed resolution of: Lkotlin/jvm/internal/Intrinsics;
    // Declaring the stdlib as androidTestImplementation was accepted, resolved, and
    // then packaged nowhere, because AGP STRIPS FROM THE TEST APK any dependency
    // already present in the app APK. That is normally right — an instrumentation
    // test usually shares the app's classloader — but the fixture is a separate
    // process, so it is not right here. FixtureActivity is written in Java instead,
    // which emits no such calls; see its KDoc.

    // Compose UI tests (createComposeRule + node finders/actions). The BOM must be on
    // the androidTest classpath too — androidTestImplementation does NOT extend
    // implementation — so ui-test-junit4's version resolves from it.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
