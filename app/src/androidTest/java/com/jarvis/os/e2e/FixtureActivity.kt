package com.jarvis.os.e2e

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A stand-in "third-party app" for the end-to-end tap tier.
 *
 * It exists because of a hard constraint in [com.jarvis.os.control.ScreenControlService]:
 * `awaitApp` and `userAppRoot` both refuse to act on JARVIS's own package, so a
 * fixture rendered inside the app under test can never be tapped. This Activity is
 * declared in the **androidTest** APK, whose package is `com.jarvis.os.test`, so to
 * the accessibility service it looks like somebody else's app — which is exactly the
 * situation the executor is built for. Whether that actually holds is what
 * `A11yProbeTest` settles before anything is built on top of it.
 *
 * **Deliberately plain Android views, no Compose and no animation.** The emulator
 * tier has a demonstrated capacity ceiling: `JarvisAppUiTest` was removed after its
 * animated `HudOrb` reliably took the GitHub VM down with "device offline" across
 * three fresh emulators. A fixture whose whole job is to expose a few labelled
 * controls has no business rendering anything expensive.
 *
 * Callbacks are recorded in [Recorder] rather than asserted here, so a test can ask
 * "did the fixture's own click handler actually run?" — the same shape as
 * `InstructionsScreenUiTest`'s `onSave` stub. Asserting that JARVIS *reported* a tap
 * would only re-test the executor's own bookkeeping; asserting the fixture reacted
 * is what proves the tap landed.
 */
class FixtureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Recorder.reset()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(PAD, PAD, PAD, PAD)
        }

        // A control whose visible text is exactly what a <<TAP|…>> would name.
        root.addView(
            Button(this).apply {
                text = TAP_TARGET
                setOnClickListener { Recorder.tapped(TAP_TARGET) }
            },
        )

        // A decoy that shares a word with the target, so the probe also shows the
        // scorer preferring an exact match over a partial one — the "tapped the
        // wrong thing" class that ScreenMatchTest covers in pure form.
        root.addView(
            Button(this).apply {
                text = DECOY
                setOnClickListener { Recorder.tapped(DECOY) }
            },
        )

        // A real editable field, so a later test can drive <<TYPE>> against
        // something that genuinely accepts ACTION_SET_TEXT.
        root.addView(
            EditText(this).apply {
                hint = FIELD_HINT
                contentDescription = FIELD_HINT
            },
        )

        root.addView(TextView(this).apply { text = "fixture ready" })
        setContentView(root)
    }

    /** What the fixture has been asked to do, readable from the test process. */
    object Recorder {
        @Volatile
        private var lastTap: String? = null

        fun reset() {
            lastTap = null
        }

        fun tapped(label: String) {
            lastTap = label
        }

        /** The label of the last control the fixture itself handled a click for. */
        fun lastTapped(): String? = lastTap
    }

    companion object {
        const val TAP_TARGET = "Add to cart"
        const val DECOY = "Add to wishlist"
        const val FIELD_HINT = "Search for groceries"

        /** The package the fixture runs in — NOT `com.jarvis.os`. */
        const val FIXTURE_PACKAGE = "com.jarvis.os.test"

        private const val PAD = 48
    }
}
