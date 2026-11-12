package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.File

/** Explicit attachment grants mounted into the session's logical images/voice namespaces. */
internal class MediaRootDocumentTree(
    private val workspace: BoundDocumentTree,
    private val media: Map<String, BoundDocumentTree>,
) : BoundDocumentTree {
    private data class Route(val tree: BoundDocumentTree, val mount: String?, val target: WorkspaceTarget) {
        fun path(): String = (target as? WorkspaceTarget.Relative)?.path
            ?: throw IllegalArgumentException("A file operation cannot target a media root")

        fun logical(snapshot: PlatformDocumentSnapshot): PlatformDocumentSnapshot {
            val prefix = mount ?: return snapshot
            val relative = when (val actual = snapshot.target) {
                WorkspaceTarget.Root -> prefix
                is WorkspaceTarget.Relative -> "$prefix/${actual.path}"
            }
            return snapshot.copy(target = WorkspaceTarget.Relative(relative))
        }
    }

    private fun route(target: WorkspaceTarget): Route {
        val path = (target as? WorkspaceTarget.Relative)?.path ?: return Route(workspace, null, target)
        require(isValidWorkspacePath(path)) { "Invalid workspace path" }
        val prefix = path.substringBefore('/')
        val tree = media[prefix] ?: return Route(workspace, null, target)
        val nested = if (path == prefix) WorkspaceTarget.Root else WorkspaceTarget.Relative(path.substringAfter('/'))
        return Route(tree, prefix, nested)
    }

    private fun route(path: String): Route = route(WorkspaceTarget.Relative(path))

    override fun stat(target: WorkspaceTarget, digest: SnapshotDigestMode): PlatformDocumentSnapshot? {
        val routed = route(target)
        return routed.tree.stat(routed.target, digest)?.let(routed::logical)
    }

    override fun listChildren(target: WorkspaceTarget, cursor: String?, pageSize: UInt): PlatformMetadataPage {
        if (target == WorkspaceTarget.Root) return listRoot(cursor, pageSize)
        val routed = route(target)
        val page = routed.tree.listChildren(routed.target, cursor, pageSize)
        return page.copy(items = page.items.map(routed::logical))
    }

    private fun listRoot(cursor: String?, pageSize: UInt): PlatformMetadataPage {
        // Keep provider cursors opaque. Synthetic mounts have their own bounded prefix page;
        // physical entries shadowed by a mount never enter the logical inventory.
        if (cursor == null || cursor.startsWith(MOUNT_CURSOR)) {
            val offset = cursor?.run { removePrefix(MOUNT_CURSOR).toInt() } ?: 0
            val names = media.keys.sorted()
            require(offset in names.indices) { "Invalid media mount cursor" }
            val entries = names.drop(offset).take(pageSize.toInt()).map { name ->
                val routed = route(name)
                val root = routed.tree.stat(WorkspaceTarget.Root, SnapshotDigestMode.METADATA_ONLY)
                    ?: throw unavailableMediaRoot()
                routed.logical(root)
            }
            val next = offset + entries.size
            return PlatformMetadataPage(entries, if (next < names.size) "$MOUNT_CURSOR$next" else WORKSPACE_CURSOR)
        }
        require(cursor.startsWith(WORKSPACE_CURSOR)) { "Invalid workspace listing cursor" }
        val providerCursor = cursor.removePrefix(WORKSPACE_CURSOR).ifEmpty { null }
        val page = workspace.listChildren(WorkspaceTarget.Root, providerCursor, pageSize)
        return page.copy(
            items = page.items.filterNot { item ->
                (item.target as? WorkspaceTarget.Relative)?.path in media
            },
            nextCursor = page.nextCursor?.let { "$WORKSPACE_CURSOR$it" },
        )
    }

    override fun ensureDirectory(path: String): PlatformDocumentSnapshot {
        val routed = route(path)
        val snapshot = if (routed.target == WorkspaceTarget.Root) {
            routed.tree.stat(WorkspaceTarget.Root, SnapshotDigestMode.METADATA_ONLY)
                ?: throw unavailableMediaRoot()
        } else {
            routed.tree.ensureDirectory(routed.path())
        }
        return routed.logical(snapshot)
    }

    override fun openRead(path: String): PlatformReadHandle {
        val routed = route(path)
        val read = routed.tree.openRead(routed.path())
        return read.copy(snapshot = routed.logical(read.snapshot))
    }

    override fun openReadByHandle(path: String, documentHandle: String): PlatformReadHandle {
        val routed = route(path)
        val read = routed.tree.openReadByHandle(routed.path(), documentHandle)
        return read.copy(snapshot = routed.logical(read.snapshot))
    }

    override fun writeFromExchange(
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome {
        val routed = route(path)
        val outcome = routed.tree.writeFromExchange(routed.path(), bytes, mode, mimeType) { snapshot ->
            arbitrate(snapshot?.let(routed::logical))
        }
        return logicalOutcome(routed, outcome)
    }

    override fun writeFromFile(
        path: String,
        source: File,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome {
        val routed = route(path)
        val outcome = routed.tree.writeFromFile(routed.path(), source, mimeType) { snapshot ->
            arbitrate(snapshot?.let(routed::logical))
        }
        return logicalOutcome(routed, outcome)
    }

    private fun logicalOutcome(routed: Route, outcome: WriteOutcome): WriteOutcome = when (outcome) {
        is WriteOutcome.Satisfied -> outcome
        is WriteOutcome.Written -> WriteOutcome.Written(routed.logical(outcome.snapshot))
    }

    override fun move(source: String, target: String): PlatformDocumentSnapshot {
        val from = route(source)
        val to = route(target)
        return to.logical(from.tree.moveTo(from.path(), to.tree, to.path()))
    }

    override fun moveTo(source: String, targetTree: BoundDocumentTree, target: String): PlatformDocumentSnapshot {
        val from = route(source)
        val to = if (targetTree is MediaRootDocumentTree) {
            targetTree.route(target)
        } else {
            Route(targetTree, null, WorkspaceTarget.Relative(target))
        }
        return to.logical(from.tree.moveTo(from.path(), to.tree, to.path()))
    }

    override fun delete(path: String) {
        val routed = route(path)
        routed.tree.delete(routed.path())
    }

    private fun unavailableMediaRoot() = PlatformActionAccessException(
        category = "storage",
        code = "media_root_unavailable",
        diagnostic = "Selected media directory is unavailable",
    )

    private companion object {
        const val MOUNT_CURSOR = "mount:"
        const val WORKSPACE_CURSOR = "workspace:"
    }
}
