package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncPlanner
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: plan S3 sync actions from provider-neutral snapshots while preserving neutral missing-remote
 *   verification safety.
 *
 * Scenarios:
 * - Given a local-only file, when planning runs, then the file is uploaded.
 * - Given local and remote snapshots without metadata, when planning runs, then the path is reported as a conflict.
 * - Given both local and remote changed since metadata, when planning runs, then the path is reported as a conflict.
 * - Given remote absence is unverified, when planning runs, then unchanged local content is not deleted.
 *
 * Observable outcomes:
 * - Planned RemoteSyncDirection/RemoteSyncReason values for each relative path.
 *
 * TDD proof:
 * - Target command: ./gradlew --no-daemon --no-configuration-cache --console=plain
 *   :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3SyncPlannerTest'
 * - Observed RED: after this contract was updated first, this test failed to compile because S3SyncPlanner.plan and
 *   planPaths still required LocalS3File, RemoteS3File, S3SyncMetadataEntity, and S3SyncAction instead of the
 *   provider-neutral RemoteSync* snapshots/actions.
 * - Why RED proves the behavior was missing: provider DTO/entity types remained part of the planner boundary, so S3
 *   planning could drift away from the shared provider-neutral action contract.
 *
 * Excludes:
 * - S3 transport, encryption codec internals, metadata DAO I/O, executor-side verification, and UI rendering.
 */
class S3SyncPlannerTest : DataFunSpec() {
    init {
        test("local only file uploads") { `local only file uploads from provider-neutral snapshots`() }

        test("first sync overlapping file reports conflict") { `first sync overlapping file reports conflict from provider-neutral snapshots`() }

        test("both changed reports conflict") { `both changed reports conflict from provider-neutral snapshots`() }

        test("remote delete removes unchanged local file") { `remote delete removes unchanged local file from provider-neutral snapshots`() }

        test(
            "cached missing remote does not delete unchanged local file",
        ) { `cached missing remote does not delete unchanged local file`() }
    }


    private val planner = S3SyncPlanner(timestampToleranceMs = 0L)

    private fun `local only file uploads from provider-neutral snapshots`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to RemoteSyncLocalSnapshot("memo.md", 20L)),
                remoteFiles = emptyMap(),
                metadata = emptyMap(),
            )

        plan.actions.map { it.direction } shouldBe listOf(RemoteSyncDirection.UPLOAD)
        plan.actions.map { it.reason } shouldBe listOf(RemoteSyncReason.LOCAL_ONLY)
    }

    private fun `first sync overlapping file reports conflict from provider-neutral snapshots`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to RemoteSyncLocalSnapshot("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteSyncRemoteSnapshot("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        plan.actions.single().direction shouldBe RemoteSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe RemoteSyncReason.CONFLICT
    }

    private fun `both changed reports conflict from provider-neutral snapshots`() {
        val metadata =
            RemoteSyncMetadataSnapshot(
                path = "memo.md",
                etag = "1",
                remoteLastModified = 10L,
                localLastModified = 10L,
                lastSyncedAt = 10L,
            )

        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to RemoteSyncLocalSnapshot("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteSyncRemoteSnapshot("memo.md", etag = "2", lastModified = 20L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe RemoteSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe RemoteSyncReason.CONFLICT
    }

    private fun `remote delete removes unchanged local file from provider-neutral snapshots`() {
        val metadata =
            RemoteSyncMetadataSnapshot(
                path = "memo.md",
                etag = "1",
                remoteLastModified = 10L,
                localLastModified = 10L,
                lastSyncedAt = 10L,
            )

        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to RemoteSyncLocalSnapshot("memo.md", 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe RemoteSyncDirection.DELETE_LOCAL
        plan.actions.single().reason shouldBe RemoteSyncReason.REMOTE_DELETED
    }

    private fun `cached missing remote does not delete unchanged local file`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to RemoteSyncLocalSnapshot("memo.md", 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf("memo.md" to metadata(path = "memo.md", lastModified = 10L)),
                missingRemoteVerificationByPath =
                    mapOf("memo.md" to RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT),
            )

        plan.actions shouldBe emptyList()
    }

    private fun metadata(
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
