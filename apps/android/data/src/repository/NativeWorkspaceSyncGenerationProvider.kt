package com.lomo.data.repository

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider

/**
 * Reads the Rust-owned [WorkspaceGenerationId] for the active Direct workspace.
 *
 * Missing Direct root or missing `generation.rec` fail closed. This provider never hashes a
 * path/URI and never mints a generation.
 */
class NativeWorkspaceSyncGenerationProvider(
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val remoteSync: RemoteSyncRepository,
) : WorkspaceSyncGenerationProvider {
    override suspend fun activeGeneration(): WorkspaceSyncGeneration {
        val root = workspaceRoot.absolutePathOrNull()
        require(!root.isNullOrBlank()) {
            "workspace generation requires an open Direct workspace root"
        }
        return WorkspaceSyncGeneration(remoteSync.loadWorkspaceGeneration(root))
    }
}
