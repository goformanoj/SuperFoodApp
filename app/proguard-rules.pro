# R8 keep rules for the release build (isMinifyEnabled = true).
#
# This app hand-rolls all its JSON with org.json (no reflective serializer like
# Gson/Moshi), so there is very little reflection to protect. The two real risks
# are the native/JNI library (TensorFlow Lite) and the Google Identity types, plus
# keeping crash reports readable. Keep rules are intentionally conservative — a
# minified release build MUST still be smoke-tested on a device before publishing,
# because R8 runtime problems (a shrunk reflective path) cannot be caught in CI.

# Keep source file + line numbers so a release crash maps back through the R8
# mapping file (app/build/outputs/mapping/release/mapping.txt) to real Kotlin lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TensorFlow Lite loads its interpreter, delegates and ops through JNI/reflection.
# The AAR ships consumer rules, but keep the public surface explicitly so the
# openWakeWord model load can never be shrunk or renamed out from under the JNI.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.**$* { *; }
-dontwarn org.tensorflow.lite.**

# Google Identity / Credential Manager (Google sign-in via a Google ID token).
# Consumer rules exist; keep the id-token request/response types defensively.
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**

# App enums are persisted to disk BY NAME and read back with valueOf() — e.g.
# DebugLog.Stage in the redacted trace log (the app-learning corpus). Keep the
# constant fields (not just values()/valueOf) so a value written by one build still
# resolves after an app update; otherwise R8 could rename the constants and every
# pre-update trace line would silently fail to parse.
-keepclassmembers enum com.jarvis.os.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Keep annotation attributes Compose / AndroidX may read at runtime. (Compose,
# coroutines, credentials and TFLite all bundle their own consumer rules, so no
# further app-specific keeps are needed today; add here if a device smoke-test of
# a minified build surfaces a stripped path.)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
