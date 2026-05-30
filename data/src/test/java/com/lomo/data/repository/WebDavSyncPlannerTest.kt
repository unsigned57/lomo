package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncPlanner and RemoteSyncPlannerCore
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: plan remote sync actions from provider-neutral local, remote, and metadata snapshots.
 *
 * Scenarios:
 * - Given a local-only file, when planning runs, then the file is uploaded.
 * - Given a remote-only file, when planning runs, then the file is downloaded.
 * - Given local and remote snapshots without metadata, when planning runs, then the path is reported as a conflict.
 * - Given matching snapshots for common sync cases, when WebDAV and S3 planners run, then provider-neutral actions match.
 *
 * Observable outcomes:
 * - Planned RemoteSyncDirection/RemoteSyncReason values and cross-provider direction/reason pairs.
 *
 * TDD proof:
 * - Target command: ./gradlew --no-daemon --no-configuration-cache --console=plain
 *   :data:testDebugUnitTest --tests 'com.lomo.data.repository.WebDavSyncPlannerTest'
 * - Observed RED: after this contract was updated first, this test failed to compile because WebDavSyncPlanner.plan
 *   still required LocalWebDavFile, RemoteWebDavFile, WebDavSyncMetadataEntity, and WebDavSyncAction instead of the
 *   provider-neutral RemoteSync* snapshots/actions.
 * - Why RED proves the behavior was missing: provider DTO/entity types remained part of the planner boundary, so
 *   WebDAV planning could drift away from the shared provider-neutral action contract.
 *
 * Excludes:
 * - WebDAV transport, S3 transport, file I/O, metadata persistence, action application, and UI rendering.
 */
class WebDavSyncPlannerTest : DataFunSpec() {
    init {
        test(
            "given provider-neutral snapshots when local changes and remote is stable then core plans upload",
        ) { `given provider-neutral snapshots when local changes and remote is stable then core plans upload`() }

        test("local only file uploads") { `local only file uploads from provider-neutral snapshots`() }

        test("remote only file downloads") { `remote only file downloads from provider-neutral snapshots`() }

        test("first sync overlapping file reports conflict instead of timestamp winner") {
            `first sync overlapping file reports conflict instead of timestamp winner from provider-neutral snapshots`()
        }

        test("both changed reports conflict") { `both changed reports conflict from provider-neutral snapshots`() }

        test("remote delete removes unchanged local file") { `remote delete removes unchanged local file from provider-neutral snapshots`() }

        test("remote delete with local modification reports conflict instead of deleting local file") {
            `remote delete with local modification reports conflict instead of deleting local file from provider-neutral snapshots`()
        }

        test("local delete removes unchanged remote file") { `local delete removes unchanged remote file from provider-neutral snapshots`() }

        test("local delete with remote modification reports conflict instead of downloading remote file") {
            `local delete with remote modification reports conflict instead of downloading remote file from provider-neutral snapshots`()
        }

        test(
            "given isomorphic snapshots when WebDAV and S3 plan then common directions match",
        ) { `given isomorphic snapshots when WebDAV and S3 plan then common directions match`() }
    }


    private val planner = WebDavSyncPlanner(timestampToleranceMs = 0L)

    private fun `given provider-neutral snapshots when local changes and remote is stable then core plans upload`() {
        val path = "memo.md"
        val core =
            RemoteSyncPlannerCore(
                timestampToleranceMs = 0L,
                policy = timestampAndEtagPolicy(),
            )
        val plan =
            core.plan(
                localFiles = mapOf(path to RemoteSyncLocalSnapshot(path = path, lastModified = 20L)),
                remoteFiles =
                    mapOf(
                        path to RemoteSyncRemoteSnapshot(path = path, etag = "etag-10", lastModified = 10L),
                    ),
                metadata = mapOf(path to remoteMetadata(path = path, lastModified = 10L)),
            )

        plan.actions shouldBe
            listOf(RemoteSyncAction(path, RemoteSyncDirection.UPLOAD, RemoteSyncReason.LOCAL_ONLY))
    }

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

    private fun `remote only file downloads from provider-neutral snapshots`() {
        val plan =
            planner.plan(
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteSyncRemoteSnapshot("memo.md", etag = "1", lastModified = 20L)),
                metadata = emptyMap(),
            )

        plan.actions.map { it.direction } shouldBe listOf(RemoteSyncDirection.DOWNLOAD)
        plan.actions.map { it.reason } shouldBe listOf(RemoteSyncReason.REMOTE_ONLY)
    }

    private fun `first sync overlapping file reports conflict instead of timestamp winner from provider-neutral snapshots`() {
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

    private fun `remote delete with local modification reports conflict instead of deleting local file from provider-neutral snapshots`() {
        val metadata =
            RemoteSyncMetadataSnapshot(
                path = "memo.md",
                etag = "1",
                remoteLastModified = 200L,
                localLastModified = 100L,
                lastSyncedAt = 100L,
            )

        val plan =
            planner.plan(
                localFiles = mapOf("memo.md" to RemoteSyncLocalSnapshot("memo.md", 150L)),
                remoteFiles = emptyMap(),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe RemoteSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe RemoteSyncReason.CONFLICT
    }

    private fun `local delete removes unchanged remote file from provider-neutral snapshots`() {
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
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteSyncRemoteSnapshot("memo.md", etag = "1", lastModified = 10L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe RemoteSyncDirection.DELETE_REMOTE
        plan.actions.single().reason shouldBe RemoteSyncReason.LOCAL_DELETED
    }

    private fun `local delete with remote modification reports conflict instead of downloading remote file from provider-neutral snapshots`() {
        val metadata =
            RemoteSyncMetadataSnapshot(
                path = "memo.md",
                etag = "1",
                remoteLastModified = 100L,
                localLastModified = 200L,
                lastSyncedAt = 100L,
            )

        val plan =
            planner.plan(
                localFiles = emptyMap(),
                remoteFiles = mapOf("memo.md" to RemoteSyncRemoteSnapshot("memo.md", etag = "2", lastModified = 250L)),
                metadata = mapOf("memo.md" to metadata),
            )

        plan.actions.single().direction shouldBe RemoteSyncDirection.CONFLICT
        plan.actions.single().reason shouldBe RemoteSyncReason.CONFLICT
    }

    private fun `given isomorphic snapshots when WebDAV and S3 plan then common directions match`() {
        val webDavPlan =
            planner.plan(
                localFiles =
                    mapOf(
                        "local-only.md" to RemoteSyncLocalSnapshot("local-only.md", 20L),
                        "unchanged.md" to RemoteSyncLocalSnapshot("unchanged.md", 10L),
                        "local-changed.md" to RemoteSyncLocalSnapshot("local-changed.md", 20L),
                        "remote-changed.md" to RemoteSyncLocalSnapshot("remote-changed.md", 10L),
                        "both-changed.md" to RemoteSyncLocalSnapshot("both-changed.md", 20L),
                        "remote-deleted.md" to RemoteSyncLocalSnapshot("remote-deleted.md", 10L),
                    ),
                remoteFiles =
                    mapOf(
                        "remote-only.md" to RemoteSyncRemoteSnapshot("remote-only.md", etag = "etag-20", lastModified = 20L),
                        "unchanged.md" to RemoteSyncRemoteSnapshot("unchanged.md", etag = "etag-10", lastModified = 10L),
                        "local-changed.md" to RemoteSyncRemoteSnapshot("local-changed.md", etag = "etag-10", lastModified = 10L),
                        "remote-changed.md" to RemoteSyncRemoteSnapshot("remote-changed.md", etag = "etag-20", lastModified = 20L),
                        "both-changed.md" to RemoteSyncRemoteSnapshot("both-changed.md", etag = "etag-20", lastModified = 20L),
                        "local-deleted.md" to RemoteSyncRemoteSnapshot("local-deleted.md", etag = "etag-10", lastModified = 10L),
                    ),
                metadata =
                    mapOf(
                        "unchanged.md" to webDavMetadata("unchanged.md", 10L),
                        "local-changed.md" to webDavMetadata("local-changed.md", 10L),
                        "remote-changed.md" to webDavMetadata("remote-changed.md", 10L),
                        "both-changed.md" to webDavMetadata("both-changed.md", 10L),
                        "remote-deleted.md" to webDavMetadata("remote-deleted.md", 10L),
                        "local-deleted.md" to webDavMetadata("local-deleted.md", 10L),
                    ),
            )
        val s3Plan =
            S3SyncPlanner(timestampToleranceMs = 0L).plan(
                localFiles =
                    mapOf(
                        "local-only.md" to RemoteSyncLocalSnapshot("local-only.md", 20L),
                        "unchanged.md" to RemoteSyncLocalSnapshot("unchanged.md", 10L),
                        "local-changed.md" to RemoteSyncLocalSnapshot("local-changed.md", 20L),
                        "remote-changed.md" to RemoteSyncLocalSnapshot("remote-changed.md", 10L),
                        "both-changed.md" to RemoteSyncLocalSnapshot("both-changed.md", 20L),
                        "remote-deleted.md" to RemoteSyncLocalSnapshot("remote-deleted.md", 10L),
                    ),
                remoteFiles =
                    mapOf(
                        "remote-only.md" to RemoteSyncRemoteSnapshot("remote-only.md", etag = "etag-20", lastModified = 20L),
                        "unchanged.md" to RemoteSyncRemoteSnapshot("unchanged.md", etag = "etag-10", lastModified = 10L),
                        "local-changed.md" to RemoteSyncRemoteSnapshot("local-changed.md", etag = "etag-10", lastModified = 10L),
                        "remote-changed.md" to RemoteSyncRemoteSnapshot("remote-changed.md", etag = "etag-20", lastModified = 20L),
                        "both-changed.md" to RemoteSyncRemoteSnapshot("both-changed.md", etag = "etag-20", lastModified = 20L),
                        "local-deleted.md" to RemoteSyncRemoteSnapshot("local-deleted.md", etag = "etag-10", lastModified = 10L),
                    ),
                metadata =
                    mapOf(
                        "unchanged.md" to s3Metadata("unchanged.md", 10L),
                        "local-changed.md" to s3Metadata("local-changed.md", 10L),
                        "remote-changed.md" to s3Metadata("remote-changed.md", 10L),
                        "both-changed.md" to s3Metadata("both-changed.md", 10L),
                        "remote-deleted.md" to s3Metadata("remote-deleted.md", 10L),
                        "local-deleted.md" to s3Metadata("local-deleted.md", 10L),
                    ),
            )

        webDavPlan.actions.map { it.path to (it.direction.name to it.reason.name) } shouldBe
            s3Plan.actions.map { it.path to (it.direction.name to it.reason.name) }
    }

    private fun timestampAndEtagPolicy() =
        object : RemoteSyncPlannerPolicy {
            override fun localChanged(
                local: RemoteSyncLocalSnapshot,
                metadata: RemoteSyncMetadataSnapshot,
                comparator: RemoteSyncChangeComparator,
            ): Boolean = comparator.changed(local.lastModified, metadata.localLastModified)

            override fun remoteChanged(
                remote: RemoteSyncRemoteSnapshot,
                metadata: RemoteSyncMetadataSnapshot,
                comparator: RemoteSyncChangeComparator,
            ): Boolean = comparator.changed(remote.lastModified, metadata.remoteLastModified) || remote.etag != metadata.etag
        }

    private fun remoteMetadata(
        path: String,
        lastModified: Long,
    ) = RemoteSyncMetadataSnapshot(
        path = path,
        etag = "etag-$lastModified",
        remoteLastModified = lastModified,
        localLastModified = lastModified,
        lastSyncedAt = lastModified,
    )

    private fun webDavMetadata(
        path: String,
        lastModified: Long,
    ) = RemoteSyncMetadataSnapshot(
        path = path,
        etag = "etag-$lastModified",
        remoteLastModified = lastModified,
        localLastModified = lastModified,
        lastSyncedAt = lastModified,
    )

    private fun s3Metadata(
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
