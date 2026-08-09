package com.jarvis.os.control

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the full `resolvePackage` path (not just `rank`) against a FAKE set of
 * installed apps, on the JVM via Robolectric. This is the level the real device
 * bug lived at: "Amazon Music" resolving to the shorter "Amazon" the launcher
 * happened to enumerate first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLauncherRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Registers a launchable app with the given label under the LAUNCHER intent. */
    private fun install(pkg: String, label: String) {
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
        }
        val activity = ActivityInfo().apply {
            this.packageName = pkg
            name = "$pkg.MainActivity"
            applicationInfo = appInfo
            nonLocalizedLabel = label
        }
        val resolve = ResolveInfo().apply {
            activityInfo = activity
            nonLocalizedLabel = label
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, resolve)
    }

    @Test
    fun `amazon music resolves to Amazon Music, not the shop or a music app`() {
        install("com.amazon.mp3", "Amazon Music")
        install("com.amazon.shopping", "Amazon")
        install("com.android.music", "Music")
        assertEquals("com.amazon.mp3", AppLauncher.resolvePackage(context, "Amazon Music"))
    }

    @Test
    fun `plain amazon resolves to the shop`() {
        install("com.amazon.mp3", "Amazon Music")
        install("com.amazon.shopping", "Amazon")
        assertEquals("com.amazon.shopping", AppLauncher.resolvePackage(context, "Amazon"))
    }

    @Test
    fun `an uninstalled app resolves to null, not a random one`() {
        install("com.whatsapp", "WhatsApp")
        install("com.android.chrome", "Chrome")
        assertNull(AppLauncher.resolvePackage(context, "Spotify"))
    }
}
