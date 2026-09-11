package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.input.InputEditorDisplayMode
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: shouldRenderMemoEditorPreview / MEMO_EDITOR_PREVIEW_DEBOUNCE_MILLIS
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: bound editor preview work to the visible preview mode.
 *
 * Scenarios:
 * - Given Edit mode, when the preview-render policy is consulted, then Markdown render is skipped.
 * - Given Preview mode, when the preview-render policy is consulted, then render is enabled with
 *   the shared 250ms debounce budget.
 *
 * Observable outcomes:
 * - boolean render gate, MEMO_EDITOR_PREVIEW_DEBOUNCE_MILLIS.
 *
 * TDD proof:
 * - Fails before the policy exists because preview work is not gated on InputEditorDisplayMode.
 *
 * Excludes:
 * - Compose preview rendering and Markdown parser internals.
 */
class MemoEditorPreviewPolicyTest : AppFunSpec() {
    init {
        test("given edit mode when preview policy is checked then rendering is disabled") {
            shouldRenderMemoEditorPreview(InputEditorDisplayMode.Edit) shouldBe false
        }

        test("given preview mode when preview policy is checked then rendering is enabled") {
            shouldRenderMemoEditorPreview(InputEditorDisplayMode.Preview) shouldBe true
            MEMO_EDITOR_PREVIEW_DEBOUNCE_MILLIS shouldBe 250L
        }
    }
}
