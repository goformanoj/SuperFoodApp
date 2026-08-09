package com.jarvis.os.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI cover for the custom-instructions screen — the append / save / forget
 * flows that used to be checkable only by hand on a device. Rendered in isolation
 * with test callbacks, so it asserts the real composable's wiring without the engine.
 */
@RunWith(AndroidJUnit4::class)
class InstructionsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapping_an_example_fills_the_editor_and_Save_reports_it() {
        var savedText: String? = null
        compose.setContent {
            MaterialTheme { InstructionsScreen(initial = "", onSave = { savedText = it }) }
        }
        // Tapping an example appends it to the (empty) editor…
        compose.onNodeWithText("Call me sir.").performScrollTo().performClick()
        // …and Save hands the editor's contents to the callback.
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Call me sir.", savedText) }
    }

    @Test
    fun Save_shows_the_saved_confirmation() {
        compose.setContent {
            MaterialTheme { InstructionsScreen(initial = "call me sir", onSave = {}) }
        }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Saved ✓").assertIsDisplayed()
    }

    @Test
    fun Forget_reports_which_learned_fact_to_remove() {
        var forgotten: String? = null
        val fact = "the user's playlist is called Pic"
        compose.setContent {
            MaterialTheme {
                InstructionsScreen(
                    initial = "",
                    learned = listOf(fact),
                    onSave = {},
                    onForget = { forgotten = it },
                )
            }
        }
        compose.onNodeWithText(fact).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Forget").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(fact, forgotten) }
    }
}
