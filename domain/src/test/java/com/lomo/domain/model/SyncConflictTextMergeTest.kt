package com.lomo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SyncConflictTextMerge
 * - Behavior focus: conservative text merge for non-overlapping sync conflicts.
 * - Observable outcomes: merged text result for safe insertions and null for overlapping edits.
 * - Red phase: Fails before the fix because no shared merge helper exists, so mergeable S3/WebDAV conflicts cannot produce a stable merged text result.
 * - Excludes: repository I/O, UI rendering, and binary file handling.
 */
class SyncConflictTextMergeTest {
    @Test
    fun `merge returns combined text for non-overlapping insertions around common anchors`() {
        val merged =
            SyncConflictTextMerge.merge(
                localText = "start\nlocal\nmiddle\nend",
                remoteText = "start\nmiddle\nremote\nend",
            )

        assertEquals("start\nlocal\nmiddle\nremote\nend", merged)
    }

    @Test
    fun `merge prefers superset segment when one side fully contains the other`() {
        val merged =
            SyncConflictTextMerge.merge(
                localText = "alpha\nbeta",
                remoteText = "alpha\nbeta\ngamma",
            )

        assertEquals("alpha\nbeta\ngamma", merged)
    }

    @Test
    fun `merge returns null for overlapping edits in the same slot`() {
        val merged =
            SyncConflictTextMerge.merge(
                localText = "start\nlocal\nend",
                remoteText = "start\nremote\nend",
            )

        assertNull(merged)
    }
}
