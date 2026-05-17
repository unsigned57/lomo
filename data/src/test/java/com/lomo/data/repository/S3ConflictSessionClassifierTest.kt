package com.lomo.data.repository


import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: S3 conflict session classification for S3SyncExecutor conflict publishing.
 * - Behavior focus: first-sync overlapping files with no stored metadata must be classified as an initial-sync preview, while established conflicts with sync metadata must stay standard conflicts.
 * - Observable outcomes: returned SyncConflictSessionKind for conflict action sets.
 * - Red phase: Fails before the fix because S3 conflict classification does not distinguish first-sync overlap from normal conflicts, so the executor can only publish generic ConflictDetected.
 * - Excludes: S3 transport, file content merging, Compose rendering, and metadata DAO I/O.
 */
class S3ConflictSessionClassifierTest : DataFunSpec() {
    init {
        test("all conflict paths without metadata become initial sync preview") { `all conflict paths without metadata become initial sync preview`() }

        test("existing metadata keeps conflict in standard session") { `existing metadata keeps conflict in standard session`() }
    }


    private fun `all conflict paths without metadata become initial sync preview`() {
        val actions =
            listOf(
                S3SyncAction(
                    path = "memo.md",
                    direction = S3SyncDirection.CONFLICT,
                    reason = S3SyncReason.CONFLICT,
                ),
            )

        val sessionKind =
            determineS3ConflictSessionKind(
                conflictActions = actions,
                metadataByPath = emptyMap(),
            )

        sessionKind shouldBe SyncConflictSessionKind.INITIAL_SYNC_PREVIEW
    }

    private fun `existing metadata keeps conflict in standard session`() {
        val actions =
            listOf(
                S3SyncAction(
                    path = "memo.md",
                    direction = S3SyncDirection.CONFLICT,
                    reason = S3SyncReason.CONFLICT,
                ),
            )
        val metadata =
            mapOf(
                "memo.md" to
                    S3SyncMetadataEntity(
                        relativePath = "memo.md",
                        remotePath = "memo.md",
                        etag = "etag-1",
                        remoteLastModified = 10L,
                        localLastModified = 10L,
                        lastSyncedAt = 10L,
                        lastResolvedDirection = "NONE",
                        lastResolvedReason = "UNCHANGED",
                    ),
            )

        val sessionKind =
            determineS3ConflictSessionKind(
                conflictActions = actions,
                metadataByPath = metadata,
            )

        sessionKind shouldBe SyncConflictSessionKind.STANDARD_CONFLICT
    }
}
