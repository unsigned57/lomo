package com.lomo.ui.component.input

import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.ui.component.markdown.MarkdownRenderState
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: Input editor preview presentation policy.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P1.
 * - Capability: preview only a typed Rust-owned render document and expose pending/failure states.
 *
 * Scenarios:
 * - Given blank editor text, when preview is shown, then the explicit blank presentation wins.
 * - Given non-blank text whose render is pending, when preview is shown, then loading is observable.
 * - Given a typed render document, when preview is shown, then that exact document is selected.
 * - Given rendering failed, when preview is shown, then failure is observable without raw fallback.
 *
 * Observable outcomes:
 * - The resolved presentation is Blank, Pending, Ready(document), or Failed.
 *
 * TDD proof:
 * - RED: the old surface only selected `previewContent ?: inputText`, so typed Ready/Pending/Failed
 *   states and fail-closed preview behavior did not compile.
 *
 * Excludes:
 * - Compose animation timing, Rust Markdown semantics, and image loading.
 *
 * Test Change Justification:
 * - Reason category: product contract changed from raw-string fallback to typed owner IR.
 * - Old behavior/assertion being replaced: nullable resolved Markdown text fell back to editor text.
 * - Why old assertion is no longer correct: fallback reparsed Markdown in Kotlin and hid owner failure.
 * - Coverage preserved by: all blank, pending, ready, and failed preview branches are asserted.
 * - Why this is not fitting the test to the implementation: the assertions enforce the public
 *   fail-closed ownership boundary, independent of Compose structure.
 */
class InputEditorPreviewContentPolicyTest : UiComponentsFunSpec() {
    init {
        test("given blank input when preview resolves then blank presentation wins") {
            resolveInputEditorPreviewPresentation(
                inputText = "",
                renderState = MarkdownRenderState.Pending,
            ) shouldBe InputEditorPreviewPresentation.Blank
        }

        test("given non-blank input when render is pending then loading remains observable") {
            resolveInputEditorPreviewPresentation(
                inputText = "# Title",
                renderState = MarkdownRenderState.Pending,
            ) shouldBe InputEditorPreviewPresentation.Pending
        }

        test("given typed document when preview resolves then exact owner document is selected") {
            val document = emptyDocument(plainText = "Title")

            val presentation =
                resolveInputEditorPreviewPresentation(
                    inputText = "# Title",
                    renderState = MarkdownRenderState.Ready(document),
                ).shouldBeInstanceOf<InputEditorPreviewPresentation.Ready>()

            presentation.document shouldBe document
        }

        test("given render failure when preview resolves then raw input is not selected") {
            resolveInputEditorPreviewPresentation(
                inputText = "# Title",
                renderState = MarkdownRenderState.Failed(code = "workspace_not_ready"),
            ) shouldBe InputEditorPreviewPresentation.Failed
        }
    }

    private fun emptyDocument(plainText: String) =
        MarkdownRenderDocument(
            sourceByteLength = plainText.encodeToByteArray().size.toULong(),
            plainText = plainText,
            tagNames = emptyList(),
            attachmentDestinations = emptyList(),
            blocks = emptyList(),
        )
}
