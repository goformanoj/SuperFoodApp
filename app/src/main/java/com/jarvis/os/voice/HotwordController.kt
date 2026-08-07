package com.jarvis.os.voice

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import com.jarvis.os.BuildConfig
import com.jarvis.os.debug.DebugLog

/**
 * A true mic-off wake word, via Picovoice Porcupine.
 *
 * Porcupine runs a tiny on-device model over its OWN audio stream and fires when
 * it hears the built-in "Jarvis" keyword. Crucially the heavy Google
 * [android.speech.SpeechRecognizer] is NOT running while it listens — nothing is
 * being transcribed — which is what makes this a real hotword rather than the
 * always-on speech-to-text the app used to run. That is the difference the user
 * asked for: reachable by voice without the recogniser always listening.
 *
 * **One mic owner, always.** The manager holds the microphone while armed, so the
 * engine must [disarm] it before it starts the recogniser and [arm] it again only
 * once the recogniser has let go. This class does no ownership *thinking* — it
 * arms and disarms when told, and the engine's single mic-owner logic decides
 * when. This is the whole reason the earlier background-wake attempt failed: two
 * recognisers on one mic. Porcupine is a different pipeline, but the discipline is
 * the same.
 *
 * **Cannot break the app or the build.** With no access key, or on any init/start
 * failure, [available] becomes false and the engine simply keeps its in-app wake
 * gate. Every Porcupine call is wrapped; nothing here throws to the caller.
 */
class HotwordController(private val context: Context) {

    private var manager: PorcupineManager? = null
    private var armed = false
    // Latches on the first failure so we stop trying and stay on the fallback,
    // rather than thrashing the mic by re-arming a broken engine every cycle.
    private var failed = false
    private var onDetected: (() -> Unit)? = null

    /** True when a hotword engine can be used: a key is set and nothing has failed yet. */
    val available: Boolean get() = HAS_KEY && !failed

    /**
     * Start listening for "Jarvis". [onDetected] fires on Porcupine's own audio
     * thread — the caller must hop to the main thread. Returns whether the engine
     * is now listening; false means the caller should fall back to the in-app gate.
     */
    fun arm(onDetected: () -> Unit): Boolean {
        if (!available) return false
        this.onDetected = onDetected
        if (armed) return true
        return try {
            if (manager == null) {
                manager = PorcupineManager.Builder()
                    .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(SENSITIVITY)
                    .build(context, PorcupineManagerCallback { this.onDetected?.invoke() })
            }
            manager?.start()
            armed = true
            DebugLog.log(DebugLog.Stage.HEARD, "hotword armed — listening for “Jarvis”")
            true
        } catch (e: Exception) {
            // A bad key, an unsupported device, or the mic still held elsewhere.
            // Give up on the hotword for this run rather than risk a mic deadlock.
            DebugLog.log(DebugLog.Stage.ERROR, "hotword unavailable: ${e.message ?: e.javaClass.simpleName}")
            failed = true
            releaseQuietly()
            false
        }
    }

    /** Stop listening and release the microphone, so the recogniser can take it. */
    fun disarm() {
        if (!armed) return
        armed = false
        try {
            manager?.stop()
            DebugLog.log(DebugLog.Stage.HEARD, "hotword disarmed")
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "hotword stop failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Fully release native resources. Call from the engine's destroy. */
    fun destroy() {
        onDetected = null
        releaseQuietly()
    }

    private fun releaseQuietly() {
        armed = false
        try {
            manager?.stop()
        } catch (e: Exception) {
            // ignore — we are tearing down
        }
        try {
            manager?.delete()
        } catch (e: Exception) {
            // ignore
        }
        manager = null
    }

    private companion object {
        val HAS_KEY = BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()

        // How readily it fires. Higher catches more but false-triggers more; 0.5 is
        // Picovoice's balanced default and a sane starting point to tune on-device.
        const val SENSITIVITY = 0.5f
    }
}
