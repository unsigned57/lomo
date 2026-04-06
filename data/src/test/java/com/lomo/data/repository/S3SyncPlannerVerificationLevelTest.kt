package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncPlanner
 * - Behavior focus: remote verification level should prevent destructive local deletions when remote absence is only cached or unknown, while still allowing uploads and verified delete propagation.
 * - Observable outcomes: planned S3SyncDirection/S3SyncReason for candidate paths under verified and unverified remote-missing inputs.
 * - Red phase: Fails before the fix because planner treats any missing remote file as authoritative and schedules DELETE_LOCAL even when the remote absence was never verified.
 * - Excludes: S3 transport, metadata DAO I/O, executor-side action verification, and UI rendering.
 */
class S3SyncPlannerVerificationLevelTest {
    private val planner = S3SyncPlanner(timestampToleranceMs = 0L)

    @Test
    fun `unknown remote absence does not delete unchanged local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to LocalS3File(path, 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            )

        assertEquals(emptyList<S3SyncDirection>(), plan.actions.map { it.direction })
    }

    @Test
    fun `unknown remote absence still uploads newer local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to LocalS3File(path, 20L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            )

        assertEquals(listOf(S3SyncDirection.UPLOAD), plan.actions.map { it.direction })
        assertEquals(listOf(S3SyncReason.LOCAL_NEWER), plan.actions.map { it.reason })
    }

    @Test
    fun `verified remote absence still deletes unchanged local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to LocalS3File(path, 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                missingRemoteVerificationByPath = mapOf(path to S3RemoteVerificationLevel.VERIFIED_REMOTE),
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            )

        assertEquals(listOf(S3SyncDirection.DELETE_LOCAL), plan.actions.map { it.direction })
        assertEquals(listOf(S3SyncReason.REMOTE_DELETED), plan.actions.map { it.reason })
    }

    private fun stableMetadata(
        path: String,
        lastModified: Long,
    ) = S3SyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = "etag-$lastModified",
        remoteLastModified = lastModified,
        localLastModified = lastModified,
        lastSyncedAt = lastModified,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )
}
