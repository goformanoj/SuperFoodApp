package com.jarvis.os.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The screen has been through two shapes. It was a free-text box with a menu of
 * sentences to paste into it; then a form of four labelled inputs, which was the
 * same thing with better content. It is now a summary of what JARVIS has been
 * told plus compact rows that open one at a time.
 *
 * These assert the wiring through that last shape — a row opens, a choice inside
 * it registers, and the pinned Save reflects whether there is anything to save.
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
        // The row has to be opened first: only the setting being changed shows a
        // control, which is what makes this a list rather than a form.
        compose.onNodeWithText("Answer length").performScrollTo().performClick()
        compose.onNodeWithText("Brief").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
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
        // Save is pinned to the bottom rather than scrolled to, so there is
        // nothing to scroll to it.
        compose.onNodeWithText("Saved").assertIsNotEnabled()
    }

    @Test
    fun Save_shows_the_saved_confirmation() {
        compose.setContent {
            MaterialTheme { InstructionsScreen(initial = "", onSave = {}) }
        }
        compose.onNodeWithText("Tone").performScrollTo().performClick()
        compose.onNodeWithText("Casual").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
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
        // By content description, not by label: removal is a quiet icon now
        // rather than a red-bordered word on every row. The description names the
        // fact, so a screen reader says which one it removes — with several rows
        // on screen, "Forget" alone would be four identical buttons.
        compose.onNodeWithContentDescription("Forget: $fact").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(fact, forgotten) }
    }

    @Test
    fun what_JARVIS_has_learned_is_shown_even_when_it_has_learned_nothing() {
        // Deliberate, and a change from before. This is the only place a user can
        // see what the assistant believes about them; a section that hides until
        // it has content means nobody discovers it exists until it already knows
        // something, which is exactly backwards for a privacy surface.
        compose.setContent {
            MaterialTheme {
                InstructionsScreen(initial = "", learned = emptyList(), onSave = {})
            }
        }
        compose.onNodeWithText("What JARVIS has picked up").performScrollTo().assertIsDisplayed()
    }
}
