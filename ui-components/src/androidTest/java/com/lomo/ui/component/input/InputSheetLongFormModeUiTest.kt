package com.lomo.ui.component.input

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * - Unit under test: InputSheet long-form mode entry and expanded preview affordance.
 * - Behavior focus: compact mode should expose long-form entry as a toolbar icon, expanded mode should swap to a collapse icon, placeholder text should disappear once the editor is expanded, and expanded mode should offer edit/preview switching for rendered Markdown inspection.
 * - Observable outcomes: expand/collapse content descriptions in the toolbar, placeholder visibility only in compact mode, visible edit/preview labels in expanded mode, and rendered markdown text when preview is selected.
 * - Red phase: Fails before the fix because expanded mode still renders a top-bar "long-form" title even though the product contract now wants only the edit/preview switch plus collapse affordance; the companion source contract test locks that removal.
 * - Excludes: animation curve smoothness, IME behavior, and memo submit persistence.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the test looked up and rejected a resource-backed "long-form mode" title in both compact and expanded states.
 * - Why old assertion is no longer correct: the product contract now removes that title resource entirely, so this instrumentation test should no longer depend on a deleted string.
 * - Coverage preserved by: the test still verifies the toolbar affordance swap, proves placeholder removal, keeps the edit/preview controls visible, waits for rendered Markdown preview output, and the companion source contract test still locks removal of the standalone title resource usage.
 * - Why this is not fitting the test to the implementation: the new assertion is stricter about the visible UI contract, not weaker.
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
        }

        assertTrue(composeRule.onAllNodesWithContentDescription(expandDescription).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithContentDescription(expandDescription).performClick()

        assertTrue(composeRule.onAllNodesWithContentDescription(collapseDescription).fetchSemanticsNodes().isNotEmpty())
        assertFalse(composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun expandedMode_exposesEditPreviewSwitchAndPreviewRendersMarkdown() {
        val inputValue = mutableStateOf(TextFieldValue("# Title"))
        val isExpanded = mutableStateOf(false)
        val displayMode = mutableStateOf(InputEditorDisplayMode.Edit)
        lateinit var editLabel: String
        lateinit var previewLabel: String
        lateinit var expandDescription: String

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state =
                        InputSheetState(
                            inputValue = inputValue.value,
                            isExpanded = isExpanded.value,
                            displayMode = displayMode.value,
                        ),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onToggleExpanded = { isExpanded.value = !isExpanded.value },
                            onCollapse = {
                                isExpanded.value = false
                                displayMode.value = InputEditorDisplayMode.Edit
                            },
                            onDisplayModeChange = { displayMode.value = it },
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        composeRule.runOnUiThread {
            expandDescription = composeRule.activity.getString(R.string.cd_expand)
            editLabel = composeRule.activity.getString(R.string.input_mode_edit)
            previewLabel = composeRule.activity.getString(R.string.input_mode_preview)
        }

        assertFalse(composeRule.onAllNodesWithText(editLabel).fetchSemanticsNodes().isNotEmpty())
        assertFalse(composeRule.onAllNodesWithText(previewLabel).fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithContentDescription(expandDescription).performClick()

        assertTrue(composeRule.onAllNodesWithText(editLabel).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText(previewLabel).fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithText(previewLabel).performClick()

        composeRule.waitUntil(5_000) {
            var renderedHeading: TextView? = null
            composeRule.runOnUiThread {
                renderedHeading =
                    composeRule.activity
                        .findViewById<View>(android.R.id.content)
                        .findFirstTextViewWithText("Title")
            }
            renderedHeading != null
        }
    }

    @Test
    fun tappingSelectedPreviewMode_collapsesExpandedSheet() {
        val inputValue = mutableStateOf(TextFieldValue("# Title"))
        val isExpanded = mutableStateOf(false)
        val displayMode = mutableStateOf(InputEditorDisplayMode.Edit)
        lateinit var expandDescription: String
        lateinit var editLabel: String
        lateinit var previewLabel: String

        composeRule.setContent {
            MaterialTheme {
                InputSheet(
                    state =
                        InputSheetState(
                            inputValue = inputValue.value,
                            isExpanded = isExpanded.value,
                            displayMode = displayMode.value,
                        ),
                    callbacks =
                        InputSheetCallbacks(
                            onInputValueChange = { inputValue.value = it },
                            onDismiss = {},
                            onToggleExpanded = { isExpanded.value = !isExpanded.value },
                            onCollapse = {
                                isExpanded.value = false
                                displayMode.value = InputEditorDisplayMode.Edit
                            },
                            onDisplayModeChange = { displayMode.value = it },
                            onSubmit = {},
                            onImageClick = {},
                        ),
                )
            }
        }

        composeRule.runOnUiThread {
            expandDescription = composeRule.activity.getString(R.string.cd_expand)
            editLabel = composeRule.activity.getString(R.string.input_mode_edit)
            previewLabel = composeRule.activity.getString(R.string.input_mode_preview)
        }

        composeRule.onNodeWithContentDescription(expandDescription).performClick()
        composeRule.onNodeWithText(previewLabel).performClick()
        composeRule.onNodeWithText(previewLabel).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(editLabel).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText(previewLabel).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun View.findFirstTextViewWithText(text: String): TextView? {
        if (this is TextView && this !is MemoInputEditText && this.text?.toString() == text) {
            return this
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findFirstTextViewWithText(text)?.let { return it }
            }
        }
        return null
    }
}
