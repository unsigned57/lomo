package com.lomo.data.repository

import android.content.Context
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class WorkspaceMediaFile(
    val filename: String,
    val bytes: ByteArray,
)

enum class WorkspaceMediaCategory(
    val logicalPrefix: String,
) {
    IMAGE("images/"),
    VOICE("voice/"),
}

interface WorkspaceMediaAccess {
    suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaFile>

    suspend fun listFilenames(category: WorkspaceMediaCategory): List<String>

    suspend fun readFileBytes(
        category: WorkspaceMediaCategory,
        filename: String,
    ): ByteArray? = listFiles(category).firstOrNull { it.filename == filename }?.bytes

    suspend fun writeFile(
        category: WorkspaceMediaCategory,
        filename: String,
        bytes: ByteArray,
    )

    suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    )
}

object NoOpWorkspaceMediaAccess : WorkspaceMediaAccess {
    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaFile> = emptyList()

    override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> = emptyList()

    override suspend fun writeFile(
        category: WorkspaceMediaCategory,
        filename: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    ) = Unit
}

@Singleton
class DefaultWorkspaceMediaAccess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val workspaceConfigSource: WorkspaceConfigSource,
    ) : WorkspaceMediaAccess {
        override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaFile> =
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

        override suspend fun writeFile(
            category: WorkspaceMediaCategory,
            filename: String,
            bytes: ByteArray,
        ) {
            val root = requireNotNull(workspaceMediaRoot(workspaceConfigSource, category)) {
                "No configured workspace root for ${category.name.lowercase(java.util.Locale.ROOT)} media restore"
            }
            if (isContentUriRoot(root)) {
                writeWorkspaceSafFile(context, category, root, filename, bytes)
            } else {
                writeWorkspaceDirectFile(File(root), filename, bytes)
            }
        }

        override suspend fun deleteFile(
            category: WorkspaceMediaCategory,
            filename: String,
        ) {
            val root = workspaceMediaRoot(workspaceConfigSource, category) ?: return
            if (isContentUriRoot(root)) {
                deleteWorkspaceSafFile(context, root, filename)
            } else {
                deleteWorkspaceDirectFile(File(root), filename)
            }
        }
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
