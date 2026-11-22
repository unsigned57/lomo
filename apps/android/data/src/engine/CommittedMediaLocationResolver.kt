package com.lomo.data.engine

import android.provider.DocumentsContract
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceMount
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import java.io.File

/** Resolves a commit's exact workspace path to the location witnessed by its storage provider. */
internal class CommittedMediaLocationResolver(
    private val workspaceMount: () -> WorkspaceMount,
    private val documents: PlatformDocumentsGateway,
) {
    fun resolve(relativePath: String): StorageLocation? {
        require(isValidWorkspacePath(relativePath)) { "Invalid committed media path" }
        val mount = workspaceMount()
        val selected = when (relativePath.substringBefore('/')) {
            "images" -> mount.attachments.images
            "voice" -> mount.attachments.recordings
            else -> null
        }
        val path = if (selected != null) relativePath.substringAfter('/') else relativePath
        val root = (selected ?: checkNotNull(mount.location) { "Media commit requires an active workspace" }).raw
        if (!root.startsWith("content:", ignoreCase = true)) {
            return File(root, path).takeIf(File::isFile)?.let { file ->
                StorageLocation(file.toPath().toUri().toASCIIString())
            }
        }
        val snapshot = documents.stat(root, WorkspaceTarget.Relative(path), SnapshotDigestMode.METADATA_ONLY)
            ?: return null
        check(snapshot.kind == DocumentKind.FILE) { "Committed media must resolve to a file: $relativePath" }
        return StorageLocation(
            DocumentsContract.buildDocumentUriUsingTree(root.toAndroidUri(), snapshot.documentId).toString(),
        )
    }
}
