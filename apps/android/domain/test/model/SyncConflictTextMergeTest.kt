package com.lomo.domain.model

import com.lomo.domain.repository.MemoIdentityConflictMerger
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SyncConflictTextMerge
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: conservatively merge text sync conflicts only when the merge is bounded and deterministic.
 *   Identity-keyed memo block merge is delegated to [MemoIdentityConflictMerger] (owner in production).
 *
 * Scenarios:
 * - Given one side is empty, when merge runs, then the non-empty side is returned.
 * - Given equal text, non-overlapping anchor insertions, a superset segment, or short disjoint memo content,
 *   when merge runs, then the safe merged text is returned.
 * - Given an identity merger that plans shared-timestamp rewrite, when merge runs, then owner-planned
 *   bytes win over disjoint concat.
 * - Given overlapping edits, uncertain segments, or an input that exceeds the configured merge budget,
 *   when merge runs, then null is returned so conflict review handles the file.
 *
 * Observable outcomes:
 * - Returned merged text, or null to decline automatic write-back.
 *
 * TDD proof:
 * - Fails before identity-keyed merge is delegated to MemoIdentityConflictMerger and before
 *   overlapping/uncertain merges decline with null.
 *
 * Excludes:
 * - Repository write-back, UI rendering, binary conflict handling, and large heap stress tests.
 * - Owner parse correctness (covered by lomo-workspace memo_shard_identity_merge_contract).
 *
 * Test Change Justification:
 * - Reason category: production memo identity merge ownership moved to workspace/store cutover.
 * - Old behavior/assertion being replaced: pure domain concat/superset merge without an injected
 *   identity merger for shared-timestamp memo shards.
 * - Why old assertion is no longer correct: production conflict recovery now prefers owner-planned
 *   identity merge over blind disjoint concatenation when timestamps collide.
 * - Coverage preserved by: empty-side, equal-text, anchor insertion, superset, budget-exceeded,
 *   and overlapping-decline cases remain asserted; identity-merger path is added.
 * - Why this is not fitting the test to the implementation: outcomes stay returned merged text or
 *   null decline, not repository write paths.
 */
class SyncConflictTextMergeTest : DomainFunSpec() {
    /** Test double: when both sides share a timestamp token, prefer the newer full shard text. */
    private val identityMerger =
        MemoIdentityConflictMerger { local, remote, localLastModified, remoteLastModified ->
            val timeToken = Regex("""- \d{1,2}:\d{2}(?::\d{2})?""")
            val localTimes = timeToken.findAll(local).map { it.value }.toSet()
            val remoteTimes = timeToken.findAll(remote).map { it.value }.toSet()
            if (localTimes.intersect(remoteTimes).isEmpty()) {
                null
            } else {
                val localIsNewer =
                    localLastModified == null ||
                        remoteLastModified == null ||
                        localLastModified >= remoteLastModified
                if (localIsNewer) local else remote
            }
        }

    init {
        test("merge keeps the non-empty side when the other side is missing") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "local only",
                    remoteText = null,
                )

            merged shouldBe "local only"
        }

        test("merge returns combined text for non-overlapping insertions around common anchors") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "start\nlocal\nmiddle\nend",
                    remoteText = "start\nmiddle\nremote\nend",
                )

            merged shouldBe "start\nlocal\nmiddle\nremote\nend"
        }

        test("merge prefers superset segment when one side fully contains the other") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "alpha\nbeta",
                    remoteText = "alpha\nbeta\ngamma",
                )

            merged shouldBe "alpha\nbeta\ngamma"
        }

        test("merge concatenates disjoint multi-line memo content with older text first") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "local idea\nlocal detail",
                    remoteText = "remote idea\nremote detail",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                )

            merged shouldBe "remote idea\nremote detail\n\nlocal idea\nlocal detail"
        }

        test("merge concatenates disjoint short memo content when timestamps differ") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "local-only note",
                    remoteText = "remote-only note",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                )

            merged shouldBe "remote-only note\n\nlocal-only note"
        }

        test("merge ignores shared blank lines when disjoint memo content is otherwise independent") {
            val expectedMergedText =
                "\n- 21:02:55 long standalone paragraph" +
                    "\n\n- 20:13:50\nitem one" +
                    "\n\n- 07:26:18 item two\n![image](img_sample.png)"

            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 20:13:50\nitem one\n\n- 07:26:18 item two\n![image](img_sample.png)",
                    remoteText = "\n- 21:02:55 long standalone paragraph",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                )

            merged shouldBe expectedMergedText
        }

        test("merge applies identity merger when both sides share a memo timestamp") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 14:30:00 edited beginning",
                    remoteText = "- 14:30:00 original beginning",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                    identityMerger = identityMerger,
                )

            merged shouldBe "- 14:30:00 edited beginning"
        }

        test("merge keeps the newer remote version via identity merger") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 14:30:00 stale local edit",
                    remoteText = "- 14:30:00 newer remote edit",
                    localLastModified = 10L,
                    remoteLastModified = 20L,
                    identityMerger = identityMerger,
                )

            merged shouldBe "- 14:30:00 newer remote edit"
        }

        test("merge uses identity merger result for shared plus distinct memos") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 09:00:00 shared edited\n\n- 10:00:00 local only",
                    remoteText = "- 09:00:00 shared original",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                    identityMerger = identityMerger,
                )

            merged shouldBe "- 09:00:00 shared edited\n\n- 10:00:00 local only"
        }

        test("merge returns null for overlapping edits in the same slot") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "start\nlocal\nend",
                    remoteText = "start\nremote\nend",
                )

            merged shouldBe null
        }

        test("merge returns null before LCS when the configured comparison budget is exceeded") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "a\nb\nc",
                    remoteText = "x\ny\nz",
                    policy = SyncConflictTextMerge.Policy(maxLineCount = 10, maxComparisonCells = 8),
                )

            merged shouldBe null
        }

        test("merge counts LCS sentinel row and column when enforcing comparison budget") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "a\nb\nc",
                    remoteText = "x\ny\nz",
                    policy = SyncConflictTextMerge.Policy(maxLineCount = 10, maxComparisonCells = 15),
                )

            merged shouldBe null
        }
    }
}
