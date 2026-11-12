package com.lomo.data.source

import android.net.Uri
import com.lomo.data.engine.storageDirectoryIdentity
import java.io.File

internal sealed interface WorkspaceVfs {
    data class Direct(val rootDir: File) : WorkspaceVfs

    data class Saf(val rootUri: Uri) : WorkspaceVfs
}

internal data class ResolvedMediaRoot(val backend: MediaStorageBackend, val vfs: WorkspaceVfs)

internal fun WorkspaceVfs.sameDirectoryAs(other: WorkspaceVfs): Boolean =
    storageDirectoryIdentity(location()) == storageDirectoryIdentity(other.location())

private fun WorkspaceVfs.location(): String = when (this) {
    is WorkspaceVfs.Direct -> rootDir.path
    is WorkspaceVfs.Saf -> rootUri.toString()
}
