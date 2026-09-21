package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.OutputStream

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

}

class DefaultWorkspaceMediaAccess(
    private val context: Context,
    private val workspaceConfigSource: WorkspaceConfigSource,
    private val writeLease: WorkspaceMutationLease,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : WorkspaceMediaAccess {
        override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> =
            workspaceMediaRoot(workspaceConfigSource, category)?.let { root ->
                if (isContentUriRoot(root)) {
                    listWorkspaceSafFiles(context, category, root, dispatcherProvider.io)
                } else {
                    listWorkspaceDirectFiles(category, File(root), dispatcherProvider.io)
                }
            }.orEmpty()

        override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> =
            workspaceMediaRoot(workspaceConfigSource, category)?.let { root ->
                if (isContentUriRoot(root)) {
                    listWorkspaceSafFilenames(context, category, root, dispatcherProvider.io)
                } else {
                    listWorkspaceDirectFilenames(category, File(root), dispatcherProvider.io)
                }
            }.orEmpty()

        override suspend fun readFileToStream(
            category: WorkspaceMediaCategory,
            filename: String,
            destination: OutputStream,
        ): Boolean =
            workspaceMediaRoot(workspaceConfigSource, category)?.let { root ->
                val safeFilename = requireWorkspaceMediaFilename(filename)
                if (isContentUriRoot(root)) {
                    readWorkspaceSafFileToStream(
                        context,
                        category,
                        root,
                        safeFilename,
                        destination,
                        dispatcherProvider.io,
                    )
                } else {
                    readWorkspaceDirectFileToStream(
                        category,
                        File(root),
                        safeFilename,
                        destination,
                        dispatcherProvider.io,
                    )
                }
            } == true

        override suspend fun writeFileFromStream(
            category: WorkspaceMediaCategory,
            filename: String,
            source: suspend (OutputStream) -> Unit,
        ) {
            writeLease.withWrite {
                val safeFilename = requireWorkspaceMediaFilename(filename)
                val root = requireNotNull(workspaceMediaRoot(workspaceConfigSource, category)) {
                    "No configured workspace root for ${category.name.lowercase(java.util.Locale.ROOT)} media restore"
                }
                if (isContentUriRoot(root)) {
                    writeWorkspaceSafFileFromStream(
                        context,
                        category,
                        root,
                        safeFilename,
                        source,
                        dispatcherProvider,
                    )
                } else {
                    writeWorkspaceDirectFileFromStream(File(root), safeFilename, source, dispatcherProvider.io)
                }
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
