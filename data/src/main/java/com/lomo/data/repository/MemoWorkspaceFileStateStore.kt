package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import java.io.File
import javax.inject.Inject

class MemoWorkspaceFileStateStore
    @Inject
    constructor(
        private val localFileStateDao: LocalFileStateDao,
        private val markdownStorageDataSource: MarkdownStorageDataSource,
    ) {
        suspend fun mainSafUri(filename: String): String? =
            localFileStateDao.getByFilename(filename, false)?.safUri

        suspend fun upsertMainState(
            filename: String,
            lastModified: Long,
            safUri: String? = null,
        ) {
            val existing = localFileStateDao.getByFilename(filename, false)
            localFileStateDao.upsert(
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = false,
                    safUri = safUri ?: existing?.safUri,
                    lastKnownModifiedTime = lastModified,
                ),
            )
        }

        suspend fun upsertTrashState(
            filename: String,
            lastModified: Long,
        ) {
            localFileStateDao.upsert(
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = true,
                    lastKnownModifiedTime = lastModified,
                ),
            )
        }

        suspend fun deleteState(
            filename: String,
            isTrash: Boolean,
        ) {
            localFileStateDao.deleteByFilename(filename, isTrash)
        }

        suspend fun resolveMainFileLastModified(
            filename: String,
            savedUriString: String?,
        ): Long = resolveSavedMainFileLastModified(filename, savedUriString) ?: System.currentTimeMillis()

        suspend fun resolveSavedMainFileLastModified(
            filename: String,
            savedUriString: String?,
        ): Long? {
            savedUriString?.let { savedPath ->
                val file = File(savedPath)
                if (file.isAbsolute && file.exists()) {
                    file.lastModified().takeIf { it > 0L }?.let { return it }
                }
            }
            return markdownStorageDataSource
                .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                ?.lastModified
        }
    }
