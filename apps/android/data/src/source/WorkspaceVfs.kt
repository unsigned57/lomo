package com.lomo.data.source

import android.net.Uri
import java.io.File

internal sealed interface WorkspaceVfs {
    data class Direct(
        val rootDir: File,
    ) : WorkspaceVfs

    data class Saf(
        val rootUri: Uri,
    ) : WorkspaceVfs
}

internal data class ResolvedMediaRoot(
    val backend: MediaStorageBackend,
    val vfs: WorkspaceVfs,
    val configuredUriMarker: String?,
)
