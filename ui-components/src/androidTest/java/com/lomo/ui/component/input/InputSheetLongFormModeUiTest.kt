package com.lomo.ui.component.input

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.R
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: InputSheet long-form mode entry and placeholder visibility.
 * - Behavior focus: compact mode should expose long-form entry as a toolbar icon, expanded mode should swap to a collapse icon, and placeholder text should disappear once the editor is expanded.
 * - Observable outcomes: expand/collapse content descriptions in the toolbar, absence of standalone long-form text row, and placeholder visibility only in compact mode.
 * - Red phase: Fails before the fix because long-form entry is rendered as a separate text button row and the empty-editor placeholder still appears in expanded mode.
 * - Excludes: animation curve smoothness, IME behavior, and memo submit persistence.
 */
@RunWith(AndroidJUnit4::class)
class InputSheetLongFormModeUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longFormToggle_staysInToolbarAndExpandedModeHidesPlaceholder() {
        val inputValue = mutableStateOf(TextFieldValue(""))
        val isExpanded = mutableStateOf(false)
        lateinit var expandDescription: String
        lateinit var collapseDescription: String
        lateinit var longFormLabel: String
        val hint = "Write something long"

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state =
                        InputSheetState(
                            inputValue = inputValue.value,
                            isExpanded = isExpanded.value,
                            hints = persistentListOf(hint),
                        ),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onToggleExpanded = { isExpanded.value = !isExpanded.value },
                            onCollapse = { isExpanded.value = false },
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        composeRule.runOnUiThread {
            expandDescription = composeRule.activity.getString(R.string.cd_expand)
            collapseDescription = composeRule.activity.getString(R.string.cd_collapse)
            longFormLabel = composeRule.activity.getString(R.string.input_long_form_mode)
        }

        assertTrue(composeRule.onAllNodesWithContentDescription(expandDescription).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty())
        assertFalse(composeRule.onAllNodesWithText(longFormLabel).fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithContentDescription(expandDescription).performClick()

        assertTrue(composeRule.onAllNodesWithContentDescription(collapseDescription).fetchSemanticsNodes().isNotEmpty())
        assertFalse(composeRule.onAllNodesWithText(longFormLabel).fetchSemanticsNodes().isNotEmpty())
        assertFalse(composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty())
    }
}
