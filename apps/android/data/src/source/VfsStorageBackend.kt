package com.lomo.data.source

import android.content.Context
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

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
        dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ) : this(createVfsStorageDelegateBundle(context, rootVfs, dispatcherProvider))

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
    dispatcherProvider: DispatcherProvider,
): VfsStorageDelegateBundle =
    when (rootVfs) {
        is WorkspaceVfs.Direct ->
            VfsStorageDelegateBundle(
                markdownDelegate = DirectMarkdownStorageBackendDelegate(rootVfs.rootDir, dispatcherProvider),
                workspaceDelegate = DirectWorkspaceConfigBackendDelegate(rootVfs.rootDir, dispatcherProvider),
                mediaDelegate = DirectMediaStorageBackendDelegate(rootVfs.rootDir, dispatcherProvider),
            )

        is WorkspaceVfs.Saf -> {
            val documentAccess = SafDocumentAccess(context, rootVfs.rootUri, dispatcherProvider = dispatcherProvider)
            VfsStorageDelegateBundle(
                markdownDelegate = SafMarkdownStorageBackendDelegate(context, rootVfs.rootUri, documentAccess),
                workspaceDelegate = SafWorkspaceConfigBackendDelegate(documentAccess),
                mediaDelegate = SafMediaStorageBackendDelegate(documentAccess),
            )
        }
    }
