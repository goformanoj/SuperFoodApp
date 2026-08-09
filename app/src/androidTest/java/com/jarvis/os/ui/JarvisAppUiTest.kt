package com.jarvis.os.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.ui.home.JarvisApp
import com.jarvis.os.voice.VoiceUiState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI cover for the app shell (`JarvisApp`) rendered in isolation — no
 * MainActivity, no AssistantEngine, no permissions. Exercises the drawer navigation
 * and the wake-word toggle, the latter wired to a real [UserPreferences] so the flip
 * is proven to persist (the SharedPreferences write engine.setBackgroundWake makes).
 */
@RunWith(AndroidJUnit4::class)
class JarvisAppUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: UserPreferences

    @Before
    fun setUp() {
        prefs = UserPreferences(context)
        prefs.backgroundWake = true // known starting state
    }

    @After
    fun tearDown() {
        prefs.backgroundWake = true
    }

    @Test
    fun drawer_opens_and_navigates_to_custom_instructions() {
        compose.setContent {
            MaterialTheme { JarvisApp(state = VoiceUiState(), onClearChat = {}) }
        }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("J.A.R.V.I.S.").assertIsDisplayed() // drawer is open
        compose.onNodeWithText("Custom instructions").performClick()
        // The instructions screen rendered — assert on its Save button, which is unique
        // to that screen (the closed drawer keeps its own labels composed off-screen).
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun wake_toggle_flips_and_persists() {
        compose.setContent {
            MaterialTheme {
                JarvisApp(
                    state = VoiceUiState(),
                    onClearChat = {},
                    backgroundWakeEnabled = { prefs.backgroundWake },
                    onSetBackgroundWake = { prefs.backgroundWake = it },
                )
            }
        }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        // The wake toggle row is a single clickable unit; tapping its title flips it.
        compose.onNodeWithText("Wake word", substring = true).performClick()
        compose.runOnIdle { assertFalse("toggling off must persist", prefs.backgroundWake) }
    }
}
