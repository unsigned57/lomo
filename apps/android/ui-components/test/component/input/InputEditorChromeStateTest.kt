package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: one committed sheet target decides which editor chrome is interactive; owning layer:
 *   ui-components input; priority: P1.
 * Scenarios:
 * - Given an empty compact card with a hint, when chrome resolves, then the hint shows, editing and
 *   formatting are interactive and there is no preview switch.
 * - Given an empty long-form editor, when chrome resolves, then the hint is gone and the preview switch,
 *   formatting and editor focus are interactive.
 * - Given the long-form preview, when chrome resolves, then the preview switch stays interactive while
 *   formatting and editor focus are withdrawn.
 * - Given a compact card asked for preview, when chrome resolves, then it still edits (preview needs the
 *   long form's height).
 * - Given an empty draft expanding, when the hint alpha resolves along the morph, then it fades from fully
 *   visible to gone and never leaves 0..1 for spring overshoot.
 * Observable outcomes: hint opacity, preview-switch, formatting and editor-focus interactivity.
 * TDD proof: Regression preservation - before: `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.input.*'`
 *   459 tests passed (target/lomo/reports/manual/large-screen-input/baseline-ui-input.out); after: the same
 *   command with these migrated assertions passes.
 * Excludes: Compose rendering, motion timing and markdown rendering.
 *
 * Test Change Justification:
 * Reason category: domain contract change — transitional presentation states removed.
 * Old behavior/assertion being replaced: resolveInputEditorChromeState(isExpanded, displayMode, text, hint)
 *   returning toggleIcon, showsStandaloneModeBar, showsDisplayModeToggle, showsFormattingToolbar and
 *   showsPreviewContent; InputSheetPresentationStateTest, InputSheetCollapseStabilityPolicyTest and
 *   InputEditorChromeTransitionStateTest asserted per-transitional-state chrome hosts.
 * Why old assertion is no longer correct: visibility now follows the continuous expansion and display
 *   progress, so only interactivity is a property of the target; showsStandaloneModeBar was always false.
 * Coverage preserved by: the same three empty-draft scenarios assert the same user-visible outcomes
 *   (hint, preview switch, formatting, focus), plus the compact-cannot-preview rule the old resolver applied.
 * Why this is not fitting the test to the implementation: each assertion is an observable affordance.
 */
class InputEditorChromeStateTest : UiComponentsFunSpec() {
    init {
        test("given an empty compact card with a hint when chrome resolves then the hint shows and editing is live") {
            val chrome = resolveInputEditorChrome(
                false,
                InputEditorDisplayMode.Edit,
                inputText = "",
                hintText = "Write something long",
            )

            assertSoftly {
                chrome.hintAlpha(expansion = 0f) shouldBe 1f
                chrome.previewToggleInteractive shouldBe false
                chrome.formattingInteractive shouldBe true
                chrome.editorAcceptsFocus shouldBe true
            }
        }

        test("given an empty long-form editor when chrome resolves then the preview switch and formatting are live") {
            val chrome = resolveInputEditorChrome(
                true,
                InputEditorDisplayMode.Edit,
                inputText = "",
                hintText = "Write something long",
            )

            assertSoftly {
                chrome.hintAlpha(expansion = 1f) shouldBe 0f
                chrome.previewToggleInteractive shouldBe true
                chrome.formattingInteractive shouldBe true
                chrome.editorAcceptsFocus shouldBe true
            }
        }

        test("given the long-form preview when chrome resolves then formatting and focus are withdrawn") {
            val chrome = resolveInputEditorChrome(
                true,
                InputEditorDisplayMode.Preview,
                inputText = "",
                hintText = "Write something long",
            )

            assertSoftly {
                chrome.hintAlpha(expansion = 1f) shouldBe 0f
                chrome.previewToggleInteractive shouldBe true
                chrome.formattingInteractive shouldBe false
                chrome.editorAcceptsFocus shouldBe false
            }
        }

        test("given a compact card asked for preview when chrome resolves then it still edits") {
            val chrome = resolveInputEditorChrome(
                false,
                InputEditorDisplayMode.Preview,
                inputText = "draft",
                hintText = "",
            )

            assertSoftly {
                chrome.displayMode shouldBe InputEditorDisplayMode.Edit
                chrome.formattingInteractive shouldBe true
                chrome.editorAcceptsFocus shouldBe true
            }
        }

        test("given an empty draft expanding when the hint alpha resolves then it fades out and stays legal") {
            val chrome = resolveInputEditorChrome(
                true,
                InputEditorDisplayMode.Edit,
                inputText = "",
                hintText = "Write something long",
            )

            assertSoftly {
                chrome.hintAlpha(expansion = 0f) shouldBe 1f
                chrome.hintAlpha(expansion = 0.25f) shouldBe 0.75f
                chrome.hintAlpha(expansion = 1.002f) shouldBe 0f
                chrome.hintAlpha(expansion = -0.002f) shouldBe 1f
            }
        }
    }
}
