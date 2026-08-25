package com.jarvis.os.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        // "Saved", without the tick it used to carry.
        //
        // The label is set in `labelLarge`, which is Michroma — a display face
        // with Latin coverage and no Dingbats, so U+2713 would render as a tofu
        // box. It only worked before because `titleSmall` was missing from the
        // Typography and fell back to Roboto, which has the glyph. Widening a
        // display font's role means every decorative character it now has to
        // carry is worth checking.
        //
        // The confirmation is stronger for it: the button also disables, so the
        // state is readable without relying on a symbol at all.
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Saved").assertIsNotEnabled()
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
