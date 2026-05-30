package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class WorkspaceMediaDescriptor(
    val filename: String,
    val sizeBytes: Long,
)

enum class WorkspaceMediaCategory(
    val logicalPrefix: String,
) {
    IMAGE("images/"),
    VOICE("voice/"),
}

interface WorkspaceMediaAccess {
    suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor>

    suspend fun listFilenames(category: WorkspaceMediaCategory): List<String>

    suspend fun readFileToStream(
        category: WorkspaceMediaCategory,
        filename: String,
        destination: OutputStream,
    ): Boolean

    suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    )

    suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    )
}

@Singleton
class DefaultWorkspaceMediaAccess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val workspaceConfigSource: WorkspaceConfigSource,
    ) : WorkspaceMediaAccess {
        override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> =
            workspaceMediaRoot(workspaceConfigSource, category)?.let { root ->
                if (isContentUriRoot(root)) {
                    listWorkspaceSafFiles(context, category, root)
                } else {
                    listWorkspaceDirectFiles(category, File(root))
                }
            } ?: emptyList()

        override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> =
            workspaceMediaRoot(workspaceConfigSource, category)?.let { root ->
                if (isContentUriRoot(root)) {
                    listWorkspaceSafFilenames(context, category, root)
                } else {
                    listWorkspaceDirectFilenames(category, File(root))
                }
            } ?: emptyList()

        override suspend fun readFileToStream(
            category: WorkspaceMediaCategory,
            filename: String,
            destination: OutputStream,
        ): Boolean =
            workspaceMediaRoot(workspaceConfigSource, category)?.let { root ->
                val safeFilename = requireWorkspaceMediaFilename(filename)
                if (isContentUriRoot(root)) {
                    readWorkspaceSafFileToStream(context, category, root, safeFilename, destination)
                } else {
                    readWorkspaceDirectFileToStream(category, File(root), safeFilename, destination)
                }
            } ?: false

        override suspend fun writeFileFromStream(
            category: WorkspaceMediaCategory,
            filename: String,
            source: suspend (OutputStream) -> Unit,
        ) {
            val safeFilename = requireWorkspaceMediaFilename(filename)
            val root = requireNotNull(workspaceMediaRoot(workspaceConfigSource, category)) {
                "No configured workspace root for ${category.name.lowercase(java.util.Locale.ROOT)} media restore"
            }
            if (isContentUriRoot(root)) {
                writeWorkspaceSafFileFromStream(context, category, root, safeFilename, source)
            } else {
                writeWorkspaceDirectFileFromStream(File(root), safeFilename, source)
            }
        }

        override suspend fun deleteFile(
            category: WorkspaceMediaCategory,
            filename: String,
        ) {
            val safeFilename = requireWorkspaceMediaFilename(filename)
            val root = workspaceMediaRoot(workspaceConfigSource, category) ?: return
            if (isContentUriRoot(root)) {
                deleteWorkspaceSafFile(context, root, safeFilename)
            } else {
                deleteWorkspaceDirectFile(File(root), safeFilename)
            }
        }
    }

internal fun requireWorkspaceMediaFilename(filename: String): String {
    require(filename.isNotBlank()) { "Workspace media filename must not be blank" }
    require('/' !in filename && '\\' !in filename) {
        "Workspace media filename must not contain paths"
    }
    require(filename != "." && filename != "..") { "Workspace media filename must not be relative" }
    return filename
}

internal suspend fun workspaceMediaRoot(
    workspaceConfigSource: WorkspaceConfigSource,
    category: WorkspaceMediaCategory,
): String? =
    when (category) {
        WorkspaceMediaCategory.IMAGE -> workspaceConfigSource.getRootFlow(StorageRootType.IMAGE).first()
        WorkspaceMediaCategory.VOICE ->
            workspaceConfigSource.getRootFlow(StorageRootType.VOICE).first()
                ?: workspaceConfigSource.getRootFlow(StorageRootType.MAIN).first()
    }
