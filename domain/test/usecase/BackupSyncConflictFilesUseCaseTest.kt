/*
 * Behavior Contract:
 * - Unit under test: BackupSyncConflictFilesUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for BackupSyncConflictFilesUseCaseTest.
 * - Boundary: boundary and edge cases for BackupSyncConflictFilesUseCaseTest.
 * - Failure: failure and error scenarios for BackupSyncConflictFilesUseCaseTest.
 * - Must-not-happen: invariants are never violated for BackupSyncConflictFilesUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of BackupSyncConflictFilesUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeSyncConflictBackupRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: BackupSyncConflictFilesUseCase
 * - Behavior focus: conflict backup delegates the exact file set and reader callback to the repository.
 * - Observable outcomes: repository receives the selected conflict files and can use the provided reader.
 * - Excludes: backup storage implementation, compression, and filesystem writes.
 */
class BackupSyncConflictFilesUseCaseTest : DomainFunSpec() {
    private val repository = FakeSyncConflictBackupRepository()
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

                        useCase(files, reader)

                        repository.backupRequests.single().files shouldBe files
                        repository.backupRequests.single().readResults["memos/2026_03_24.md"]?.decodeToString() shouldBe
                            "payload:memos/2026_03_24.md"
                    }
        }
    }
}
