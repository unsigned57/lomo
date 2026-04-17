package com.lomo.data.repository

import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: WebDavSyncPlanner
 * - Behavior focus: first-sync direction selection, conflict detection with and without metadata, and delete propagation.
 * - Observable outcomes: planned WebDavSyncDirection/WebDavSyncReason for each path.
 * - Red phase: Fails before the fix because first-sync overlapping local/remote files are resolved by timestamp instead of surfacing a conflict.
 * - Excludes: WebDAV transport, file I/O, metadata persistence, and UI rendering.
 */
class WebDavSyncPlannerTest {
    private val planner = WebDavSyncPlanner(timestampToleranceMs = 0L)

    @Test
    fun `local only file uploads`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 20L)),
                remoteFiles = emptyMap(),
                metadata = emptyMap(),
            )

        assertEquals(listOf(WebDavSyncDirection.UPLOAD), plan.actions.map { it.direction })
        assertEquals(listOf(WebDavSyncReason.LOCAL_ONLY), plan.actions.map { it.reason })
    }

    @Test
    fun `remote only file downloads`() {
        val plan =
            planner.plan(
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        assertEquals(listOf(WebDavSyncDirection.DOWNLOAD), plan.actions.map { it.direction })
        assertEquals(listOf(WebDavSyncReason.REMOTE_ONLY), plan.actions.map { it.reason })
    }

    @Test
    fun `first sync overlapping file reports conflict instead of timestamp winner`() {
        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to LocalWebDavFile("memo.md", 30L)),
                remoteFiles = mapOf("memo.md" to RemoteWebDavFile("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        assertEquals(WebDavSyncDirection.CONFLICT, plan.actions.single().direction)
        assertEquals(WebDavSyncReason.CONFLICT, plan.actions.single().reason)
    }

    @Test
    fun `both changed reports conflict`() {
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

        assertEquals(WebDavSyncDirection.CONFLICT, plan.actions.single().direction)
        assertEquals(WebDavSyncReason.CONFLICT, plan.actions.single().reason)
    }

    @Test
    fun `remote delete removes unchanged local file`() {
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

        assertEquals(WebDavSyncDirection.DELETE_LOCAL, plan.actions.single().direction)
        assertEquals(WebDavSyncReason.REMOTE_DELETED, plan.actions.single().reason)
    }

    @Test
    fun `remote delete with local modification reports conflict instead of deleting local file`() {
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

        assertEquals(WebDavSyncDirection.CONFLICT, plan.actions.single().direction)
        assertEquals(WebDavSyncReason.CONFLICT, plan.actions.single().reason)
    }

    @Test
    fun `local delete removes unchanged remote file`() {
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

        assertEquals(WebDavSyncDirection.DELETE_REMOTE, plan.actions.single().direction)
        assertEquals(WebDavSyncReason.LOCAL_DELETED, plan.actions.single().reason)
    }

    @Test
    fun `local delete with remote modification reports conflict instead of downloading remote file`() {
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

        assertEquals(WebDavSyncDirection.CONFLICT, plan.actions.single().direction)
        assertEquals(WebDavSyncReason.CONFLICT, plan.actions.single().reason)
    }
}
