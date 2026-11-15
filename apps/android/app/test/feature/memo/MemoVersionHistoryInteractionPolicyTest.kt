package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given the current revision, when its card policy is resolved, then it is static and highlighted.
 * - Given historical revisions during restore, when their card policies are resolved, then only the target is
 *   busy and other revisions remain noninteractive.
 * - Unit under test: version-history card interaction policy in MemoVersionHistorySheet
 * - Capability: current revisions must stay visually emphasized while only true historical revisions remain tappable for restore.
 * - Observable outcomes: card interaction mode and highlight style selected from revision/current-restore state.
 * - TDD proof: Fails before the fix because the sheet relies on a disabled clickable Card for current or restoring items, which mutes the current highlight and keeps all behavior buried in Compose wiring instead of an explicit policy.
 * - Excludes: ModalBottomSheet host behavior, Markdown rendering internals, and repository restore execution.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoVersionHistoryInteractionPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including current revision uses a static highlighted card;
 * historical revision uses tappable restore card when idle.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoVersionHistoryInteractionPolicyTest : AppFunSpec() {
    init {
        test("current revision uses a static highlighted card") {
            val presentation =
                resolveVersionHistoryCardPresentation(
                    version = revision(id = "current", isCurrent = true),
                    isRestoreInProgress = false,
                    restoringRevisionId = null,
                )

            (presentation.interaction) shouldBe (VersionHistoryCardInteraction.Static)
            (presentation.highlight) shouldBe (VersionHistoryCardHighlight.Current)
            (presentation.isBusy) shouldBe (false)
        }
    }

    init {
        test("historical revision uses tappable restore card when idle") {
            val presentation =
                resolveVersionHistoryCardPresentation(
                    version = revision(id = "historical", isCurrent = false),
                    isRestoreInProgress = false,
                    restoringRevisionId = null,
                )

            (presentation.interaction) shouldBe (VersionHistoryCardInteraction.Restore)
            (presentation.highlight) shouldBe (VersionHistoryCardHighlight.Standard)
            (presentation.isBusy) shouldBe (false)
        }
    }

    init {
        test("restore in progress turns non-target historical revisions into static cards") {
            val presentation =
                resolveVersionHistoryCardPresentation(
                    version = revision(id = "historical", isCurrent = false),
                    isRestoreInProgress = true,
                    restoringRevisionId = "another",
                )

            (presentation.interaction) shouldBe (VersionHistoryCardInteraction.Static)
            (presentation.highlight) shouldBe (VersionHistoryCardHighlight.Standard)
            (presentation.isBusy) shouldBe (false)
        }
    }

    init {
        test("restore target is marked busy while restore is in progress") {
            val presentation =
                resolveVersionHistoryCardPresentation(
                    version = revision(id = "historical", isCurrent = false),
                    isRestoreInProgress = true,
                    restoringRevisionId = "historical",
                )

            (presentation.interaction) shouldBe (VersionHistoryCardInteraction.Static)
            (presentation.highlight) shouldBe (VersionHistoryCardHighlight.Standard)
            (presentation.isBusy) shouldBe (true)
        }
    }
}

private fun revision(id: String, isCurrent: Boolean): MemoRevision = MemoRevision(
    revisionId = id,
    parentRevisionId = null,
    memoId = "memo-1",
    commitId = "commit-$id",
    batchId = null,
    createdAt = 1L,
    origin = MemoRevisionOrigin.LOCAL_EDIT,
    summary = "",
    lifecycleState = MemoRevisionLifecycleState.ACTIVE,
    memoContent = "content-$id",
    isCurrent = isCurrent,
)
