package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SyncConflictTextMerge
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: conservatively merge text sync conflicts only when the merge is bounded and deterministic.
 *
 * Scenarios:
 * - Given one side is empty, when merge runs, then the non-empty side is returned.
 * - Given equal text, non-overlapping anchor insertions, a superset segment, or short disjoint memo content,
 *   when merge runs, then the safe merged text is returned.
 * - Given both sides hold memo blocks that share a header timestamp (the same memo edited on each side),
 *   when merge runs, then the newer file's version replaces the block instead of duplicating it, while
 *   memos with distinct timestamps are still unioned.
 * - Given overlapping edits, uncertain segments, or an input that exceeds the configured merge budget,
 *   when merge runs, then null is returned so conflict review handles the file.
 * - Given the configured budget is smaller than the actual LCS matrix including sentinel row/column,
 *   when merge runs, then null is returned before allocating the matrix.
 *
 * Observable outcomes:
 * - Returned merged text, or null to decline automatic write-back.
 *
 * Test Change Justification:
 * - Reason category: SyncConflictTextMerge production code gained memo-dedup merge, budget-aware LCS, and
 *   anchor-based detection. Tests must cover the new merge strategies and budget constraints.
 * - Old behavior/assertion being replaced: merge only handled simple text diff; no memo dedup, no budget gating.
 * - Why old assertion is no longer correct: the merge engine now returns null for overlapping edits
 *   and performs memo-block-level deduplication for shared-timestamp blocks.
 * - Coverage preserved by: keeping existing empty-side and equal-text scenarios, adding budget,
 *   anchor, overlap, and multi-block memo dedup scenarios.
 * - Why this is not fitting the test to the implementation: each new case exercises a distinct
 *   observable merge outcome (null, merged text, deduplicated block), not internal LCS details.
 *
 * TDD proof:
 * - Fails before the fix because merge has no injectable policy/budget and always attempts the LCS path.
 * - RED memo-dedup follow-up: an edited single-memo shard (same timestamp, rewritten first line) was
 *   concatenated into two blocks ("…original beginning\n\n…edited beginning") instead of keeping the
 *   newer single block, because disjoint memo content was unconditionally appended older-first.
 *
 * Excludes:
 * - Repository write-back, UI rendering, binary conflict handling, and large heap stress tests.
 */
class SyncConflictTextMergeTest : DomainFunSpec() {
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

        test("merge keeps the newer local version when both sides hold the same edited memo timestamp") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 14:30:00 edited beginning",
                    remoteText = "- 14:30:00 original beginning",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                )

            merged shouldBe "- 14:30:00 edited beginning"
        }

        test("merge keeps the newer remote version when both sides hold the same edited memo timestamp") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 14:30:00 stale local edit",
                    remoteText = "- 14:30:00 newer remote edit",
                    localLastModified = 10L,
                    remoteLastModified = 20L,
                )

            merged shouldBe "- 14:30:00 newer remote edit"
        }

        test("merge deduplicates the shared-timestamp memo while keeping distinct memos") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 09:00:00 shared edited\n\n- 10:00:00 local only",
                    remoteText = "- 09:00:00 shared original",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
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
