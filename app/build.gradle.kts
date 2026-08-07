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
// Picovoice access key for the Porcupine wake-word engine. Same rule: provided at
// build time, never committed, empty when unset — and when empty the app falls
// back to the in-app wake gate, so an unset key is harmless.
val picovoiceAccessKey: String = (project.findProperty("PICOVOICE_ACCESS_KEY") as String?)
    ?: System.getenv("PICOVOICE_ACCESS_KEY")
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
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"$picovoiceAccessKey\"")
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

    // Porcupine on-device wake-word engine ("Jarvis" is a built-in keyword). A
    // tiny always-on model, so JARVIS can be summoned without the heavy speech
    // recogniser transcribing everything. Used only when a Picovoice access key
    // is present; otherwise the app falls back to the in-app wake gate.
    implementation("ai.picovoice:porcupine-android:3.0.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Plain JVM unit tests for the pure-Kotlin logic (marker parsers, session
    // state machine). No device or emulator needed — these run in CI on every push.
    testImplementation("junit:junit:4.13.2")
}
