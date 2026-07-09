package com.lomo.domain.repository

import com.lomo.domain.model.SyncConflictFile

interface SyncConflictBackupRepository {
    suspend fun backupFiles(
        files: List<SyncConflictFile>,
        localFileReader: suspend (String) -> ByteArray?,
    )
}
