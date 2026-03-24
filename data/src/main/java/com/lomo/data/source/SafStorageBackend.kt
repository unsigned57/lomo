package com.lomo.data.source

import android.content.Context
import android.net.Uri

/**
 * Storage backend implementation using Storage Access Framework (SAF).
 * Handles content:// URIs obtained from the document picker.
 */
class SafStorageBackend private constructor(
    markdownDelegate: SafMarkdownStorageBackendDelegate,
    workspaceDelegate: SafWorkspaceConfigBackendDelegate,
    mediaDelegate: SafMediaStorageBackendDelegate,
) : MarkdownStorageBackend by markdownDelegate,
    WorkspaceConfigBackend by workspaceDelegate,
    MediaStorageBackend by mediaDelegate {
    constructor(
        context: Context,
        rootUri: Uri,
        subDir: String? = null,
    ) : this(createSafStorageDelegateBundle(context, rootUri, subDir))

    private constructor(
        bundle: SafStorageDelegateBundle,
    ) : this(
        markdownDelegate = bundle.markdownDelegate,
        workspaceDelegate = bundle.workspaceDelegate,
        mediaDelegate = bundle.mediaDelegate,
    )
}

private data class SafStorageDelegateBundle(
    val markdownDelegate: SafMarkdownStorageBackendDelegate,
    val workspaceDelegate: SafWorkspaceConfigBackendDelegate,
    val mediaDelegate: SafMediaStorageBackendDelegate,
)

private fun createSafStorageDelegateBundle(
    context: Context,
    rootUri: Uri,
    subDir: String?,
): SafStorageDelegateBundle {
    val documentAccess = SafDocumentAccess(context, rootUri, subDir)
    return SafStorageDelegateBundle(
        markdownDelegate = SafMarkdownStorageBackendDelegate(context, rootUri, documentAccess),
        workspaceDelegate = SafWorkspaceConfigBackendDelegate(documentAccess),
        mediaDelegate = SafMediaStorageBackendDelegate(context, documentAccess),
    )
}
