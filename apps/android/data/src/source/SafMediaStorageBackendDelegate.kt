package com.lomo.data.source

import android.provider.DocumentsContract
import com.lomo.data.engine.ContentResolverPlatformDocumentsGateway
import com.lomo.data.engine.PlatformDocumentSnapshot
import com.lomo.data.engine.SnapshotDigestMode
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses the same selected-document root as the session's SAF read/write capability. */
internal class SafMediaStorageBackendDelegate(private val documentAccess: SafDocumentAccess) : MediaStorageBackend {
    private val documents = ContentResolverPlatformDocumentsGateway(documentAccess.contentResolver)

    override suspend fun listImageFiles(scope: ImageScanScope): List<Pair<String, String>> = withContext(
        documentAccess.ioDispatcher,
    ) {
        val root = documentAccess.treeUri.toString()
        val directories = ArrayDeque<WorkspaceTarget>().apply { add(WorkspaceTarget.Root) }
        val images = mutableListOf<Pair<String, String>>()
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = directories.removeFirst()
            var cursor: String? = null
            do {
                val page = documents.listChildren(root, directory, cursor, MEDIA_PAGE_SIZE)
                currentCoroutineContext().ensureActive()
                for (entry in page.items) {
                    val path = (entry.target as WorkspaceTarget.Relative).path
                    when (entry.kind) {
                        DocumentKind.DIRECTORY -> if (path.substringAfterLast('/') !in MEDIA_PRIVATE_LANES &&
                            scope.includesDirectory(path)
                        ) {
                            directories.add(entry.target)
                        }

                        DocumentKind.FILE -> if (scope.includesFile(path) && isImageAttachmentFileName(path)) {
                            images += path to location(entry)
                        }
                    }
                }
                cursor = page.nextCursor
            } while (cursor != null)
        }
        images
    }

    override suspend fun getImageLocation(filename: String): String? = withContext(documentAccess.ioDispatcher) {
        val snapshot = documents.stat(
            documentAccess.treeUri.toString(),
            WorkspaceTarget.Relative(filename),
            SnapshotDigestMode.METADATA_ONLY,
        )
        snapshot?.takeIf { it.kind == DocumentKind.FILE }?.let(::location)
    }

    private fun location(snapshot: PlatformDocumentSnapshot): String =
        DocumentsContract.buildDocumentUriUsingTree(documentAccess.treeUri, snapshot.documentId).toString()

    private companion object {
        const val MEDIA_PAGE_SIZE = 256u
        val MEDIA_PRIVATE_LANES =
            setOf(".git", ".lomo", ".lomo-media-stage", ".lomo-media-trash", ".lomo-media-delete-intents")
    }
}
