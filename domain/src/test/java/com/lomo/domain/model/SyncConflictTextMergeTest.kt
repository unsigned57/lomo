package com.lomo.domain.model

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SyncConflictTextMerge
 * - Behavior focus: conservative text merge for non-overlapping sync conflicts.
 * - Observable outcomes: merged text result for safe insertions and null for overlapping edits.
 * - TDD proof: Fails before the fix because no shared merge helper exists, so mergeable S3/WebDAV conflicts cannot produce a stable merged text result.
 * - Excludes: repository I/O, UI rendering, and binary file handling.
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
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "- 20:13:50\nitem one\n\n- 07:26:18 item two\n![image](img_sample.png)",
                    remoteText = "\n- 21:02:55 long standalone paragraph",
                    localLastModified = 20L,
                    remoteLastModified = 10L,
                )

            merged shouldBe "\n- 21:02:55 long standalone paragraph\n\n- 20:13:50\nitem one\n\n- 07:26:18 item two\n![image](img_sample.png)"
        }

        test("merge returns null for overlapping edits in the same slot") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "start\nlocal\nend",
                    remoteText = "start\nremote\nend",
                )

            merged shouldBe null
        }
    }
}
