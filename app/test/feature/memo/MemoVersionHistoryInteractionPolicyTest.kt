package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: version-history card interaction policy in MemoVersionHistorySheet
 * - Behavior focus: current revisions must stay visually emphasized while only true historical revisions remain tappable for restore.
 * - Observable outcomes: card interaction mode and highlight style selected from revision/current-restore state.
 * - Red phase: Fails before the fix because the sheet relies on a disabled clickable Card for current or restoring items, which mutes the current highlight and keeps all behavior buried in Compose wiring instead of an explicit policy.
 * - Excludes: ModalBottomSheet host behavior, Markdown rendering internals, and repository restore execution.
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

private fun revision(
    id: String,
    isCurrent: Boolean,
): MemoRevision =
    MemoRevision(
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
