package com.lomo.domain.usecase

import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.repository.SyncConflictBackupRepository

class BackupSyncConflictFilesUseCase(
    private val repository: SyncConflictBackupRepository,
) {
    suspend operator fun invoke(
        files: List<SyncConflictFile>,
        localFileReader: suspend (String) -> ByteArray?,
    ) {
        repository.backupFiles(files, localFileReader)
    }
}
