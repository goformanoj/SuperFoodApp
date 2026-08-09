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
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // On-device wake word (openWakeWord models run via TensorFlow Lite). No key,
    // no account — the "hey jarvis" model and its two feature models are bundled
    // in assets. Lets JARVIS be summoned by voice with the heavy speech recogniser
    // switched off, and (via the hotword service) from any app.
    //
    // 2.17.0, not 2.16.1: on 2.16.1 the melspectrogram model's dynamic-length input
    // overflowed a CONV_2D at prepare time on-device ("BytesRequired ... overflowed")
    // — a shape-inference bug in that runtime's allocator, not the model (the newer
    // LiteRT runtime handles the identical model). This is a runtime version bump,
    // device-independent, not a per-device workaround.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Plain JVM unit tests for the pure-Kotlin logic (marker parsers, session
    // state machine). No device or emulator needed — these run in CI on every push.
    testImplementation("junit:junit:4.13.2")
}
