package com.lomo.data.repository


import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: WebDavSyncPlanner
 * - Behavior focus: first-sync direction selection, conflict detection with and without metadata, and delete propagation.
 * - Observable outcomes: planned WebDavSyncDirection/WebDavSyncReason for each path.
 * - Red phase: Fails before the fix because first-sync overlapping local/remote files are resolved by timestamp instead of surfacing a conflict.
 * - Excludes: WebDAV transport, file I/O, metadata persistence, and UI rendering.
 */
class WebDavSyncPlannerTest : DataFunSpec() {
    init {
        test("local only file uploads") { `local only file uploads`() }

        test("remote only file downloads") { `remote only file downloads`() }

        test("first sync overlapping file reports conflict instead of timestamp winner") { `first sync overlapping file reports conflict instead of timestamp winner`() }

        test("both changed reports conflict") { `both changed reports conflict`() }

        test("remote delete removes unchanged local file") { `remote delete removes unchanged local file`() }

        test("remote delete with local modification reports conflict instead of deleting local file") { `remote delete with local modification reports conflict instead of deleting local file`() }

        test("local delete removes unchanged remote file") { `local delete removes unchanged remote file`() }

        test("local delete with remote modification reports conflict instead of downloading remote file") { `local delete with remote modification reports conflict instead of downloading remote file`() }
    }


    private val planner = WebDavSyncPlanner(timestampToleranceMs = 0L)

    private fun `local only file uploads`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 20L)),
                remoteFiles = emptyMap(),
                metadata = emptyMap(),
            )

        plan.actions.map { it.direction } shouldBe listOf(WebDavSyncDirection.UPLOAD)
        plan.actions.map { it.reason } shouldBe listOf(WebDavSyncReason.LOCAL_ONLY)
    }

    private fun `remote only file downloads`() {
        val plan =
            planner.plan(
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        plan.actions.map { it.direction } shouldBe listOf(WebDavSyncDirection.DOWNLOAD)
        plan.actions.map { it.reason } shouldBe listOf(WebDavSyncReason.REMOTE_ONLY)
    }

    private fun `first sync overlapping file reports conflict instead of timestamp winner`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        plan.actions.single().direction shouldBe WebDavSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe WebDavSyncReason.CONFLICT
    }

    private fun `both changed reports conflict`() {
        val metadata =
            WebDavSyncMetadataEntity(
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
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "2", lastModified = 20L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe WebDavSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe WebDavSyncReason.CONFLICT
    }

    private fun `remote delete removes unchanged local file`() {
        val metadata =
            WebDavSyncMetadataEntity(
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
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 10L)),
                remoteFiles = emptyMap(),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe WebDavSyncDirection.DELETE_LOCAL
        plan.actions.single().reason shouldBe WebDavSyncReason.REMOTE_DELETED
    }

    private fun `remote delete with local modification reports conflict instead of deleting local file`() {
        val metadata =
            WebDavSyncMetadataEntity(
                relativePath = "memo.md",
                remotePath = "memo.md",
                etag = "1",
                remoteLastModified = 200L,
                localLastModified = 100L,
                lastSyncedAt = 100L,
                lastResolvedDirection = "NONE",
                lastResolvedReason = "UNCHANGED",
            )

        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 150L)),
                remoteFiles = emptyMap(),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe WebDavSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe WebDavSyncReason.CONFLICT
    }

    private fun `local delete removes unchanged remote file`() {
        val metadata =
            WebDavSyncMetadataEntity(
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
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "1", lastModified = 10L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe WebDavSyncDirection.DELETE_REMOTE
        plan.actions.single().reason shouldBe WebDavSyncReason.LOCAL_DELETED
    }

    private fun `local delete with remote modification reports conflict instead of downloading remote file`() {
        val metadata =
            WebDavSyncMetadataEntity(
                relativePath = "memo.md",
                remotePath = "memo.md",
                etag = "1",
                remoteLastModified = 100L,
                localLastModified = 200L,
                lastSyncedAt = 100L,
                lastResolvedDirection = "NONE",
                lastResolvedReason = "UNCHANGED",
            )

        val plan =
            planner.plan(
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "2", lastModified = 250L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe WebDavSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe WebDavSyncReason.CONFLICT
    }
}
