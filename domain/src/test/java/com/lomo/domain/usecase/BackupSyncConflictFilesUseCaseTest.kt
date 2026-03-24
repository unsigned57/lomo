package com.lomo.domain.usecase

import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.repository.SyncConflictBackupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: BackupSyncConflictFilesUseCase
 * - Behavior focus: conflict backup delegates the exact file set and reader callback to the repository.
 * - Observable outcomes: repository receives the selected conflict files and can use the provided reader.
 * - Excludes: backup storage implementation, compression, and filesystem writes.
 */
class BackupSyncConflictFilesUseCaseTest {
    private val repository: SyncConflictBackupRepository = mockk(relaxed = true)
    private val useCase = BackupSyncConflictFilesUseCase(repository)

    @Test
    fun `invoke forwards files and local reader to repository`() =
        runTest {
            val files =
                listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "local",
                        remoteContent = "remote",
                        isBinary = false,
                    ),
                )
            val reader: suspend (String) -> ByteArray? = { "payload:$it".encodeToByteArray() }
            coEvery { repository.backupFiles(files, reader) } returns Unit

            useCase(files, reader)

            coVerify(exactly = 1) { repository.backupFiles(files, reader) }
        }
}
