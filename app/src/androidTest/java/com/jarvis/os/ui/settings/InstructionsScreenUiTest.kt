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
 * Compose UI cover for the custom-instructions screen.
 *
 * The screen used to be a free-text box with a menu of sentences to paste into
 * it; it now asks questions and takes taps, composing the one string the model is
 * sent from the answers. These assert that wiring — the chips, the save state and
 * the forget flow — in isolation, with test callbacks and no engine.
 */
@RunWith(AndroidJUnit4::class)
class InstructionsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choosing_an_answer_length_saves_it_as_an_instruction() {
        // The screen no longer offers sentences to paste into a box; it asks a
        // question and takes a tap. What reaches the model is still one string,
        // composed from the answers.
        var savedText: String? = null
        compose.setContent {
            MaterialTheme { InstructionsScreen(initial = "", onSave = { savedText = it }) }
        }
        compose.onNodeWithText("Brief").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Keep answers to one sentence unless I ask for detail.", savedText)
        }
    }

    @Test
    fun Save_is_inert_until_something_actually_changes() {
        // Opening the screen and leaving it alone must not offer a save. A
        // primary action that is always live invites a tap that does nothing.
        compose.setContent {
            MaterialTheme { InstructionsScreen(initial = "Call me sir.", onSave = {}) }
        }
        compose.onNodeWithText("Saved").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun Save_shows_the_saved_confirmation() {
        compose.setContent {
            MaterialTheme { InstructionsScreen(initial = "", onSave = {}) }
        }
        compose.onNodeWithText("Casual").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        // "Saved", without the tick it used to carry.
        //
        // The label is set in `labelLarge`, which is Michroma — a display face
        // with Latin coverage and no Dingbats, so U+2713 would render as a tofu
        // box. It only worked before because `titleSmall` was missing from the
        // Typography and fell back to Roboto, which has the glyph.
        //
        // The confirmation is stronger without it: the button also disables, so
        // the state is readable with no symbol at all.
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
