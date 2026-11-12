package com.lomo.data.engine.media

import android.content.Context
import java.io.File

/** Name of the private stage root for every workspace. */
internal const val HOST_MEDIA_STAGE_ROOT_NAME = "lomo-host-media-stage"

/** Staging and its lease journal are installation-private for Direct and SAF workspaces. */
fun interface MediaStageRootProvider {
    fun requireRoot(): String
}

internal class WorkspaceMediaStageRootProvider(private val context: Context) : MediaStageRootProvider {
    override fun requireRoot(): String = File(context.filesDir, HOST_MEDIA_STAGE_ROOT_NAME).also { root ->
        check(root.isDirectory || root.mkdirs()) { "Cannot create private media stage directory: $root" }
    }.absolutePath
}
