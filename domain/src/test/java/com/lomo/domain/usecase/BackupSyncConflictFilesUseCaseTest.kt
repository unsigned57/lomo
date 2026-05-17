/*
 * Test Contract:
 * - Unit under test: BackupSyncConflictFilesUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for BackupSyncConflictFilesUseCaseTest.
 * - Boundary: boundary and edge cases for BackupSyncConflictFilesUseCaseTest.
 * - Failure: failure and error scenarios for BackupSyncConflictFilesUseCaseTest.
 * - Must-not-happen: invariants are never violated for BackupSyncConflictFilesUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of BackupSyncConflictFilesUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.testing.DomainFunSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: BackupSyncConflictFilesUseCase
 * - Behavior focus: conflict backup delegates the exact file set and reader callback to the repository.
 * - Observable outcomes: repository receives the selected conflict files and can use the provided reader.
 * - Excludes: backup storage implementation, compression, and filesystem writes.
 */
class BackupSyncConflictFilesUseCaseTest : DomainFunSpec() {
    private val repository: SyncConflictBackupRepository = mockk(relaxed = true)
    private val useCase = BackupSyncConflictFilesUseCase(repository)
    init {
        test("invoke forwards files and local reader to repository") {
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
    }
}
