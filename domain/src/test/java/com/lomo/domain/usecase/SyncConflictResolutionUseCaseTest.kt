package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncInboxConflictResolutionResult
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.WebDavSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SyncConflictResolutionUseCase
 * - Behavior focus: source-specific conflict resolution, mapped failure exceptions, and refresh side effects.
 * - Observable outcomes: thrown domain exception type and payload, resolver invocation path, and refresh invocation count.
 * - Red phase: Fails before the fix because S3 conflict sets are not dispatched through the new resolver path, so successful resolutions skip refresh and S3 failures are not mapped to domain exceptions.
 * - Excludes: repository implementation internals, network/filesystem behavior, and UI rendering.
 */
class SyncConflictResolutionUseCaseTest {
    private val gitSyncRepository: GitSyncRepository = mockk()
    private val webDavSyncRepository: WebDavSyncRepository = mockk()
    private val s3SyncRepository: S3SyncRepository = mockk()
    private val syncInboxRepository: SyncInboxRepository = mockk()
    private val memoRepository: MemoRepository = mockk()

    private val useCase =
        SyncConflictResolutionUseCase(
            gitSyncRepository = gitSyncRepository,
            webDavSyncRepository = webDavSyncRepository,
            s3SyncRepository = s3SyncRepository,
            syncInboxRepository = syncInboxRepository,
            memoRepository = memoRepository,
        )

    @Test
    fun `git source resolves conflicts and refreshes memos on success`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.GIT)
            val resolution = sampleResolution()
            coEvery { gitSyncRepository.resolveConflicts(resolution, conflictSet) } returns GitSyncResult.Success("resolved")
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase.resolve(conflictSet, resolution)

            coVerify(exactly = 1) { gitSyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 0) { webDavSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 0) { s3SyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `git source maps git error to failure exception and skips refresh`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.GIT)
            val resolution = sampleResolution()
            val cause = IllegalStateException("git failed")
            coEvery {
                gitSyncRepository.resolveConflicts(resolution, conflictSet)
            } returns GitSyncResult.Error(code = GitSyncErrorCode.CONFLICT, message = "conflict", exception = cause)

            val thrown = runCatching { useCase.resolve(conflictSet, resolution) }.exceptionOrNull()

            assertTrue(thrown is GitSyncFailureException)
            assertEquals(GitSyncErrorCode.CONFLICT, (thrown as GitSyncFailureException).code)
            assertEquals("conflict", thrown.message)
            assertSame(cause, thrown.cause)
            coVerify(exactly = 1) { gitSyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `webdav source resolves conflicts and refreshes memos on success`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.WEBDAV)
            val resolution = sampleResolution()
            coEvery {
                webDavSyncRepository.resolveConflicts(resolution, conflictSet)
            } returns WebDavSyncResult.Success("resolved")
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase.resolve(conflictSet, resolution)

            coVerify(exactly = 0) { gitSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 1) { webDavSyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 0) { s3SyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `webdav source maps webdav error to failure exception and skips refresh`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.WEBDAV)
            val resolution = sampleResolution()
            val cause = IllegalArgumentException("webdav failed")
            coEvery {
                webDavSyncRepository.resolveConflicts(resolution, conflictSet)
            } returns WebDavSyncResult.Error(code = WebDavSyncErrorCode.CONNECTION_FAILED, message = "connection failed", exception = cause)

            val thrown = runCatching { useCase.resolve(conflictSet, resolution) }.exceptionOrNull()

            assertTrue(thrown is WebDavSyncFailureException)
            assertEquals(WebDavSyncErrorCode.CONNECTION_FAILED, (thrown as WebDavSyncFailureException).code)
            assertEquals("connection failed", thrown.message)
            assertSame(cause, thrown.cause)
            coVerify(exactly = 1) { webDavSyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `s3 source resolves conflicts and refreshes memos on success`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.S3)
            val resolution = sampleResolution()
            coEvery { s3SyncRepository.resolveConflicts(resolution, conflictSet) } returns S3SyncResult.Success("resolved")
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase.resolve(conflictSet, resolution)

            coVerify(exactly = 0) { gitSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 0) { webDavSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 1) { s3SyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `s3 source returns pending result without refreshing memos`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.S3)
            val resolution = sampleResolution()
            val pending = conflictSet.copy(files = emptyList())
            coEvery {
                s3SyncRepository.resolveConflicts(resolution, conflictSet)
            } returns S3SyncResult.Conflict(message = "Pending conflicts remain", conflicts = pending)

            val result = useCase.resolve(conflictSet, resolution)

            assertEquals(SyncConflictResolutionResult.Pending(pending), result)
            coVerify(exactly = 1) { s3SyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `s3 source maps s3 error to failure exception and skips refresh`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.S3)
            val resolution = sampleResolution()
            val cause = IllegalStateException("s3 failed")
            coEvery {
                s3SyncRepository.resolveConflicts(resolution, conflictSet)
            } returns S3SyncResult.Error(code = S3SyncErrorCode.BUCKET_ACCESS_FAILED, message = "bucket failed", exception = cause)

            val thrown = runCatching { useCase.resolve(conflictSet, resolution) }.exceptionOrNull()

            assertTrue(thrown is S3SyncFailureException)
            assertEquals(S3SyncErrorCode.BUCKET_ACCESS_FAILED, (thrown as S3SyncFailureException).code)
            assertEquals("bucket failed", thrown.message)
            assertSame(cause, thrown.cause)
            coVerify(exactly = 1) { s3SyncRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `none source skips resolver and still refreshes memos`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.NONE)
            val resolution = sampleResolution()
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase.resolve(conflictSet, resolution)

            coVerify(exactly = 0) { gitSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 0) { webDavSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 0) { s3SyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `inbox source resolves conflicts and refreshes memos on success`() =
        runTest {
            val conflictSet = conflictSet(SyncBackendType.INBOX)
            val resolution = sampleResolution()
            coEvery { syncInboxRepository.resolveConflicts(resolution, conflictSet) } returns SyncInboxConflictResolutionResult.Resolved
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase.resolve(conflictSet, resolution)

            coVerify(exactly = 0) { gitSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 0) { webDavSyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 0) { s3SyncRepository.resolveConflicts(any(), any()) }
            coVerify(exactly = 1) { syncInboxRepository.resolveConflicts(resolution, conflictSet) }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    private fun conflictSet(source: SyncBackendType): SyncConflictSet =
        SyncConflictSet(
            source = source,
            files =
                listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "local",
                        remoteContent = "remote",
                        isBinary = false,
                    ),
                ),
            timestamp = 123L,
        )

    private fun sampleResolution(): SyncConflictResolution =
        SyncConflictResolution(
            perFileChoices =
                mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                ),
        )
}
