package com.lomo.data.repository

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



import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncPlanner
 * - Behavior focus: remote verification level should prevent destructive local deletions when remote absence is only cached or unknown, while still allowing uploads and verified delete propagation.
 * - Observable outcomes: planned S3SyncDirection/S3SyncReason for candidate paths under verified and unverified remote-missing inputs.
 * - TDD proof: Fails before the fix because planner treats any missing remote file as authoritative and schedules DELETE_LOCAL even when the remote absence was never verified.
 * - Excludes: S3 transport, metadata DAO I/O, executor-side action verification, and UI rendering.
 */
class S3SyncPlannerVerificationLevelTest : DataFunSpec() {
    init {
        test("unknown remote absence does not delete unchanged local file") { `unknown remote absence does not delete unchanged local file`() }

        test("unknown remote absence still uploads newer local file") { `unknown remote absence still uploads newer local file`() }

        test("verified remote absence still deletes unchanged local file") { `verified remote absence still deletes unchanged local file`() }
    }


    private val planner = S3SyncPlanner(timestampToleranceMs = 0L)

    private fun `unknown remote absence does not delete unchanged local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to LocalS3File(path, 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            )

        plan.actions.map { it.direction } shouldBe emptyList<S3SyncDirection>()
    }

    private fun `unknown remote absence still uploads newer local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to LocalS3File(path, 20L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            )

        plan.actions.map { it.direction } shouldBe listOf(S3SyncDirection.UPLOAD)
        plan.actions.map { it.reason } shouldBe listOf(S3SyncReason.LOCAL_NEWER)
    }

    private fun `verified remote absence still deletes unchanged local file`() {
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

        plan.actions.map { it.direction } shouldBe listOf(S3SyncDirection.DELETE_LOCAL)
        plan.actions.map { it.reason } shouldBe listOf(S3SyncReason.REMOTE_DELETED)
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
