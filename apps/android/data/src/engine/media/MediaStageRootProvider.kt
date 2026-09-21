package com.lomo.data.engine.media

import android.content.Context
import java.io.File

/** Name of the private stage root used when the workspace has no Direct filesystem path. */
internal const val HOST_MEDIA_STAGE_ROOT_NAME = "lomo-host-media-stage"

/**
 * Single authority for the media root that owns staging.
 *
 * The Direct workspace path is preferred so promote can publish into the real workspace; a private
 * host root is used only when no Direct root exists. Every staged-media owner must resolve the same
 * root, or artifact/lease records would land in different stage directories.
 */
fun interface MediaStageRootProvider {
    fun requireRoot(): String
}

internal class WorkspaceMediaStageRootProvider(
    private val context: Context,
    private val workspaceRoot: WorkspaceFilesystemRoot,
) : MediaStageRootProvider {
    override fun requireRoot(): String =
        workspaceRoot.absolutePathOrNull()
            ?: File(context.filesDir, HOST_MEDIA_STAGE_ROOT_NAME).absolutePath.also { path ->
                File(path).mkdirs()
            }
}
