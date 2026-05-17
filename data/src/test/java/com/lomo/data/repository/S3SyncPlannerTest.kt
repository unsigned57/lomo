package com.lomo.data.repository


import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: S3SyncPlanner
 * - Behavior focus: first-sync conflict detection, delete propagation, and direction selection from local/remote metadata changes.
 * - Observable outcomes: planned S3SyncDirection/S3SyncReason for each relative path.
 * - Red phase: Fails before the fix because S3 sync planning does not exist, so real S3 sync cannot safely reconcile local and remote changes.
 * - Excludes: S3 transport, encryption codec internals, metadata DAO I/O, and UI rendering.
 */
class S3SyncPlannerTest : DataFunSpec() {
    init {
        test("local only file uploads") { `local only file uploads`() }

        test("first sync overlapping file reports conflict") { `first sync overlapping file reports conflict`() }

        test("both changed reports conflict") { `both changed reports conflict`() }

        test("remote delete removes unchanged local file") { `remote delete removes unchanged local file`() }
    }


    private val planner = S3SyncPlanner(timestampToleranceMs = 0L)

    private fun `local only file uploads`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalS3File("memo.md", 20L)),
                remoteFiles = emptyMap(),
                metadata = emptyMap(),
            )

        plan.actions.map { it.direction } shouldBe listOf(S3SyncDirection.UPLOAD)
        plan.actions.map { it.reason } shouldBe listOf(S3SyncReason.LOCAL_ONLY)
    }

    private fun `first sync overlapping file reports conflict`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalS3File("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteS3File("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        plan.actions.single().direction shouldBe S3SyncDirection.CONFLICT
        plan.actions.single().reason shouldBe S3SyncReason.CONFLICT
    }

    private fun `both changed reports conflict`() {
        val metadata =
            S3SyncMetadataEntity(
                relativePath = "memo.md",
                remotePath = "memo.md",
                etag = "1",
                remoteLastModified = 10L,
                localLastModified = 10L,
                lastSyncedAt = 10L,
                lastResolvedDirection = "NONE",
                lastResolvedReason = "UNCHANGED",
            )

        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalS3File("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteS3File("memo.md", etag = "2", lastModified = 20L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe S3SyncDirection.CONFLICT
        plan.actions.single().reason shouldBe S3SyncReason.CONFLICT
    }

    private fun `remote delete removes unchanged local file`() {
        val metadata =
            S3SyncMetadataEntity(
                relativePath = "memo.md",
                remotePath = "memo.md",
                etag = "1",
                remoteLastModified = 10L,
                localLastModified = 10L,
                lastSyncedAt = 10L,
                lastResolvedDirection = "NONE",
                lastResolvedReason = "UNCHANGED",
            )

        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalS3File("memo.md", 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe S3SyncDirection.DELETE_LOCAL
        plan.actions.single().reason shouldBe S3SyncReason.REMOTE_DELETED
    }
}
