package com.jarvis.os.e2e

import android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.control.ScreenControlService
import com.jarvis.os.control.ScreenStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The PROBE for the end-to-end tap tier — deliberately one test, not a suite.
 *
 * The test pyramid has six layers and **not one of them drives a tap**. The two
 * existing instrumented tests load a model and render a single Compose screen. That
 * gap is exactly why the 2026-08-14 device trace could contain integration bugs no
 * pure test could catch: the loop planning against an unrendered screen, a failed
 * launch nobody read, a first-move Back ending an errand.
 *
 * Before building a suite on this, two things have to be settled, and only a real
 * Android runtime can settle them:
 *
 *  1. **Can the accessibility service be enabled from a test at all?** It needs the
 *     `enabled_accessibility_services` secure setting written via shell.
 *  2. **Does an androidTest-APK Activity present a different package?**
 *     `ScreenControlService.awaitApp` and `userAppRoot` both refuse to act when the
 *     foreground package is JARVIS's own, so if the fixture reads as
 *     `com.jarvis.os` the whole approach is dead and the fallback is a separate
 *     `:fixtures` module installed by CI.
 *
 * Both are asserted here explicitly rather than assumed, so a failure names which
 * one broke instead of just saying a tap did not land.
 *
 * **Kept to one test on purpose.** The emulator tier has a demonstrated hard
 * ceiling — the suite reliably crashed the GitHub VM at ten tests, and
 * `JarvisAppUiTest` took down three fresh emulators in a row. This runs in its own
 * non-gating CI job so that if a11y-in-emulator turns out to be as flaky as the
 * team already found it, it cannot red the proven-green pyramid.
 */
@RunWith(AndroidJUnit4::class)
class A11yProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * The automation connection — obtained with **`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`**,
     * which is the whole reason this probe now works.
     *
     * The first run failed with the service never binding, and the diagnostics it
     * printed named the cause precisely:
     *
     * ```
     * settings enabled_accessibility_services = com.jarvis.os/…ScreenControlService
     * settings accessibility_enabled          = 1
     * AccessibilityManager.isEnabled          = true
     * AccessibilityManager enabled services   = (none)
     * installed services                      = …, com.jarvis.os/.control.ScreenControlService
     * ```
     *
     * The shell write stuck, the service was installed, accessibility was globally
     * on — and the enabled list was still empty. Nothing had failed; something had
     * actively switched it off. That something is `UiAutomation` itself: connecting
     * it suppresses every other accessibility service by default, and the test's own
     * `shell()` helper was what connected it. The instrumentation used to enable the
     * service was disabling it.
     *
     * A single accessor, used for every shell call, so the connection is never
     * established without the flag — the flag only applies to the connection as it
     * is created, so one unflagged call anywhere would reintroduce the suppression.
     */
    private val automation: UiAutomation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )

    @After
    fun disableService() {
        // Leave the emulator as we found it, so a later test in the same run is not
        // surprised by a live accessibility service.
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
    }

    @Test
    fun the_service_binds_and_a_real_tap_reaches_a_third_party_fixture() {
        // --- 1. Enable the real ScreenControlService -------------------------
        shell("settings put secure enabled_accessibility_services $SERVICE_COMPONENT")
        shell("settings put secure accessibility_enabled 1")

        val bound = waitFor(BIND_TIMEOUT_MS) { ScreenControlService.isRunning() }
        assertTrue(
            // The first run of this probe failed here with nothing but "never
            // bound", which named no cause and would have invited exactly the
            // guess-then-guess-again cycle that cost this project six red builds.
            // So the failure now carries what the system actually thinks.
            buildString {
                append("the accessibility service never bound within ${BIND_TIMEOUT_MS}ms.\n")
                append("  settings enabled_accessibility_services = ")
                append(shell("settings get secure enabled_accessibility_services").trim())
                append("\n  settings accessibility_enabled = ")
                append(shell("settings get secure accessibility_enabled").trim())
                append("\n  AccessibilityManager.isEnabled = ")
                append(accessibilityManager().isEnabled)
                append("\n  AccessibilityManager enabled services = ")
                append(
                    accessibilityManager()
                        .getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)
                        .joinToString { it.id }
                        .ifEmpty { "(none)" },
                )
                append("\n  installed services = ")
                append(
                    accessibilityManager()
                        .installedAccessibilityServiceList
                        .joinToString { it.id }
                        .ifEmpty { "(none)" },
                )
                append("\n  test process = ")
                append(instrumentation.context.packageName)
                append(" / target = ")
                append(instrumentation.targetContext.packageName)
                append(
                    "\nIf the setting stuck and the service is installed but never bound, " +
                        "the settings route is not enough on this image and the tier needs " +
                        "a different way in. If the setting did NOT stick, the shell write " +
                        "is the problem, not accessibility.",
                )
            },
            bound,
        )
        val service = ScreenControlService.instance
        assertNotNull("isRunning() was true but instance was null", service)

        // --- 2. Put the fixture in front -------------------------------------
        // The Intent must be built from the TEST context, not the target one.
        // The fixture is declared in the androidTest APK (com.jarvis.os.test);
        // building it from targetContext aimed the explicit component at
        // com.jarvis.os/…FixtureActivity, which does not exist — the first run
        // after the binding fix died exactly there with ActivityNotFoundException.
        // That error is itself the first hard evidence the two packages really are
        // distinct, which is what the assertion below goes on to require.
        val testContext = instrumentation.context
        testContext.startActivity(
            Intent(testContext, FixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        // THE constraint. If this reads com.jarvis.os, awaitApp/userAppRoot will
        // refuse every step and the androidTest-APK approach cannot work.
        val front = waitForValue(FOREGROUND_TIMEOUT_MS) {
            service!!.rootInActiveWindow?.packageName?.toString()
        }
        assertEquals(
            "the fixture must present a DIFFERENT package than the app under test — " +
                "ScreenControlService refuses to act on its own package, so if this " +
                "is com.jarvis.os the fallback is a separate :fixtures module",
            FixtureActivity.FIXTURE_PACKAGE,
            front,
        )

        // --- 3. Drive one real tap through the public executor ---------------
        val done = CountDownLatch(1)
        var reportedOk = false
        instrumentation.runOnMainSync {
            service!!.runSteps(
                steps = listOf(ScreenStep.Tap(FixtureActivity.TAP_TARGET)),
                // No model is reachable from a test, so a failed step must fail
                // rather than call out for a replacement plan.
                recover = false,
            ) { ok, _ ->
                reportedOk = ok
                done.countDown()
            }
        }
        assertTrue(
            "runSteps never called back within ${STEP_TIMEOUT_MS}ms",
            done.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue("the executor reported the tap as failed", reportedOk)

        // The assertion that actually matters: the FIXTURE's own click handler ran.
        // Trusting the executor's own "ok" would only re-test its bookkeeping — the
        // exact false-success class that once announced "Playing the Thriller
        // video" four times while doing nothing.
        val tapped = waitForValue(CALLBACK_TIMEOUT_MS) { FixtureActivity.Recorder.lastTapped() }
        assertEquals(
            "the fixture's own click callback did not fire for the target control",
            FixtureActivity.TAP_TARGET,
            tapped,
        )
        // And it hit the right one: "Add to wishlist" shares a word with the target
        // and sits right beside it.
        assertTrue(
            "the decoy must not have been tapped",
            FixtureActivity.Recorder.lastTapped() != FixtureActivity.DECOY,
        )
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Runs [command] as shell and waits for it to finish.
     *
     * The output is read to the end rather than the descriptor simply being
     * closed: `executeShellCommand` runs asynchronously, and closing early can
     * return before the setting has actually been written — which would show up
     * later as the far more confusing "the service never bound".
     */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).use { String(it.readBytes()) }

    private fun accessibilityManager(): AccessibilityManager =
        instrumentation.targetContext
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean =
        waitForValue(timeoutMs) { if (condition()) true else null } == true

    /** Polls [read] until it returns non-null, or the timeout expires. */
    private fun <T> waitForValue(timeoutMs: Long, read: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return read()
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 40_000L
        const val SERVICE_COMPONENT =
            "com.jarvis.os/com.jarvis.os.control.ScreenControlService"
        const val FOREGROUND_TIMEOUT_MS = 10_000L
        const val STEP_TIMEOUT_MS = 20_000L
        const val CALLBACK_TIMEOUT_MS = 5_000L
        const val POLL_MS = 200L
    }
}
