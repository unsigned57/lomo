package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: SyncConflictTextMerge
 * - Behavior focus: conservative text merge for non-overlapping sync conflicts.
 * - Observable outcomes: merged text result for safe insertions and null for overlapping edits.
 * - Red phase: Fails before the fix because no shared merge helper exists, so mergeable S3/WebDAV conflicts cannot produce a stable merged text result.
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
    }
    init {
        test("merge returns combined text for non-overlapping insertions around common anchors") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "start\nlocal\nmiddle\nend",
                    remoteText = "start\nmiddle\nremote\nend",
                )

            merged shouldBe "start\nlocal\nmiddle\nremote\nend"
        }
    }
    init {
        test("merge prefers superset segment when one side fully contains the other") {
            val merged =
                SyncConflictTextMerge.merge(
                    localText = "alpha\nbeta",
                    remoteText = "alpha\nbeta\ngamma",
                )

            merged shouldBe "alpha\nbeta\ngamma"
        }
    }
    init {
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
    }
    init {
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
    }
    init {
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
    }
    init {
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
