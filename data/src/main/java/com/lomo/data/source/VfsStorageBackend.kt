package com.lomo.data.source

import android.content.Context

internal class VfsStorageBackend private constructor(
    markdownDelegate: MarkdownStorageBackend,
    workspaceDelegate: WorkspaceConfigBackend,
    mediaDelegate: MediaStorageBackend,
) : MarkdownStorageBackend by markdownDelegate,
    WorkspaceConfigBackend by workspaceDelegate,
    MediaStorageBackend by mediaDelegate {
    constructor(
        context: Context,
        rootVfs: WorkspaceVfs,
        secureWipeBeforeDeleteEnabled: suspend () -> Boolean = { false },
    ) : this(createVfsStorageDelegateBundle(context, rootVfs, secureWipeBeforeDeleteEnabled))

    private constructor(
        bundle: VfsStorageDelegateBundle,
    ) : this(
        markdownDelegate = bundle.markdownDelegate,
        workspaceDelegate = bundle.workspaceDelegate,
        mediaDelegate = bundle.mediaDelegate,
    )
}

private data class VfsStorageDelegateBundle(
    val markdownDelegate: MarkdownStorageBackend,
    val workspaceDelegate: WorkspaceConfigBackend,
    val mediaDelegate: MediaStorageBackend,
)

private fun createVfsStorageDelegateBundle(
    context: Context,
    rootVfs: WorkspaceVfs,
    secureWipeBeforeDeleteEnabled: suspend () -> Boolean,
): VfsStorageDelegateBundle =
    when (rootVfs) {
        is WorkspaceVfs.Direct ->
            VfsStorageDelegateBundle(
                markdownDelegate = DirectMarkdownStorageBackendDelegate(rootVfs.rootDir, secureWipeBeforeDeleteEnabled),
                workspaceDelegate = DirectWorkspaceConfigBackendDelegate(rootVfs.rootDir),
                mediaDelegate = DirectMediaStorageBackendDelegate(rootVfs.rootDir),
            )

        is WorkspaceVfs.Saf -> {
            val documentAccess = SafDocumentAccess(context, rootVfs.rootUri)
            VfsStorageDelegateBundle(
                markdownDelegate = SafMarkdownStorageBackendDelegate(context, rootVfs.rootUri, documentAccess),
                workspaceDelegate = SafWorkspaceConfigBackendDelegate(documentAccess),
                mediaDelegate = SafMediaStorageBackendDelegate(context, documentAccess),
            )
        }
    }
