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



import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncPlanner
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: use provider-neutral remote-absence verification to prevent destructive local deletions when remote
 *   absence is cached or unknown, while still allowing uploads and verified delete propagation.
 *
 * Scenarios:
 * - Given remote absence is unverified and local content is unchanged, when planning runs, then no local delete is planned.
 * - Given remote absence is unverified and local content is newer, when planning runs, then an upload is planned.
 * - Given remote absence is verified and local content is unchanged, when planning runs, then a local delete is planned.
 *
 * - Observable outcomes: planned RemoteSyncDirection/RemoteSyncReason for candidate paths under verified and
 *   unverified remote-missing inputs.
 *
 * TDD proof:
 * - Target command: ./kotlin test
 *   :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3SyncPlannerVerificationLevelTest'
 * - Observed RED: this contract was updated first to pass RemoteSyncRemoteAbsenceVerification, which failed to
 *   compile while S3SyncPlanner still accepted provider-specific S3RemoteVerificationLevel.
 * - Why RED proves the behavior was missing: the planner contract still exposed S3 provider vocabulary instead of
 *   the shared RemoteSync planner boundary concept.
 *
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
                localFiles = mapOf(path to RemoteSyncLocalSnapshot(path, 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                defaultMissingRemoteVerification = RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
            )

        plan.actions.map { it.direction } shouldBe emptyList<RemoteSyncDirection>()
    }

    private fun `unknown remote absence still uploads newer local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to RemoteSyncLocalSnapshot(path, 20L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                defaultMissingRemoteVerification = RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
            )

        plan.actions.map { it.direction } shouldBe listOf(RemoteSyncDirection.UPLOAD)
        plan.actions.map { it.reason } shouldBe listOf(RemoteSyncReason.LOCAL_NEWER)
    }

    private fun `verified remote absence still deletes unchanged local file`() {
        val path = "memo.md"
        val plan =
            planner.planPaths(
                paths = listOf(path),
                localFiles = mapOf(path to RemoteSyncLocalSnapshot(path, 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf(path to stableMetadata(path = path, lastModified = 10L)),
                missingRemoteVerificationByPath = mapOf(path to RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT),
                defaultMissingRemoteVerification = RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
            )

        plan.actions.map { it.direction } shouldBe listOf(RemoteSyncDirection.DELETE_LOCAL)
        plan.actions.map { it.reason } shouldBe listOf(RemoteSyncReason.REMOTE_DELETED)
    }

    private fun stableMetadata(
        path: String,
        lastModified: Long,
    ) = RemoteSyncMetadataSnapshot(
        path = path,
        etag = "etag-$lastModified",
        remoteLastModified = lastModified,
        localLastModified = lastModified,
        lastSyncedAt = lastModified,
    )
}
