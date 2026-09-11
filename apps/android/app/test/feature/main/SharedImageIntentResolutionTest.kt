package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: shared-image launch intent resolution.
 * - Owning layer: app.
 * - Priority tier: P0.
 * - Capability: every accepted shared-image intent reaches a terminal state, so a failed import
 *   cannot strand the intent in the queue with no way to observe or retry it.
 *
 * Scenarios:
 * - Given an import that succeeds, when it reports the workspace destination, then the markdown is
 *   appended, the editor is shown, and the intent is consumed.
 * - Given an import that fails, when it reports failure, then the intent is still consumed and no
 *   markdown is appended.
 * - Given no configured image directory, when the intent runs, then the directory guide is
 *   requested and the intent is consumed instead of being retried forever.
 *
 * Observable outcomes:
 * - Appended markdown paths, editor-visibility requests, guide requests, consumed intent ids.
 *
 * TDD proof:
 * - RED on 2026-08-26 because the intent was consumed only inside the success callback: a failed
 *   or blocked import left the queue head in place, and its LaunchedEffect key never changed, so
 *   nothing ever moved the intent again.
 *
 * Excludes:
 * - Media staging, Rust promote, Compose rendering, and error message wording.
 */
class SharedImageIntentResolutionTest : AppFunSpec() {
    init {
        test("a successful import appends markdown, shows the editor, and consumes the intent") {
            val appended = mutableListOf<String>()
            var editorShown = 0
            var guideRequested = 0
            val consumed = mutableListOf<Long>()

            resolveSharedImageIntent(
                intentId = 7L,
                imageDirectory = "images",
                onRequireImageDirectory = { guideRequested += 1 },
                onSaveImage = { onResult, _ -> onResult("media/a.png") },
                onAppendImageMarkdown = { path -> appended += path },
                onEnsureEditorVisible = { editorShown += 1 },
                onConsume = { id -> consumed += id },
            )

            appended shouldBe listOf("media/a.png")
            editorShown shouldBe 1
            guideRequested shouldBe 0
            consumed shouldBe listOf(7L)
        }

        test("a failed import still reaches a terminal state and consumes the intent") {
            val appended = mutableListOf<String>()
            val consumed = mutableListOf<Long>()

            resolveSharedImageIntent(
                intentId = 9L,
                imageDirectory = "images",
                onRequireImageDirectory = {},
                onSaveImage = { _, onError -> onError() },
                onAppendImageMarkdown = { path -> appended += path },
                onEnsureEditorVisible = {},
                onConsume = { id -> consumed += id },
            )

            appended shouldBe emptyList()
            consumed shouldBe listOf(9L)
        }

        test("a missing image directory requests the guide and consumes the intent") {
            var guideRequested = 0
            val consumed = mutableListOf<Long>()
            var saveAttempts = 0

            resolveSharedImageIntent(
                intentId = 11L,
                imageDirectory = null,
                onRequireImageDirectory = { guideRequested += 1 },
                onSaveImage = { _, _ -> saveAttempts += 1 },
                onAppendImageMarkdown = {},
                onEnsureEditorVisible = {},
                onConsume = { id -> consumed += id },
            )

            guideRequested shouldBe 1
            saveAttempts shouldBe 0
            consumed shouldBe listOf(11L)
        }
    }
}
