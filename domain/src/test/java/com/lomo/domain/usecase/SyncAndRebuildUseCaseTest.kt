package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Behavior focus: force vs best-effort sync, refresh ordering, and cancellation or error propagation.
 * - Observable outcomes: thrown exception type, refresh execution, and collaborator call ordering.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: repository internals, transport implementation details, and UI rendering.
 */
class SyncAndRebuildUseCaseTest {
    private val memoRepository: MemoRepository = mockk()
    private val gitSyncRepository: GitSyncRepository = mockk()
    private val webDavSyncRepository: WebDavSyncRepository = mockk()
    private val s3SyncRepository: S3SyncRepository = mockk()
    private val syncPolicyRepository: SyncPolicyRepository = mockk()
    private val useCase =
        SyncAndRebuildUseCase(
            memoRepository = memoRepository,
            gitSyncRepository = gitSyncRepository,
            webDavSyncRepository = webDavSyncRepository,
            s3SyncRepository = s3SyncRepository,
            syncPolicyRepository = syncPolicyRepository,
        )

    @Test
    fun `non-force git sync cancellation is rethrown and refresh is skipped`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
            every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(true)
            val cancellation = CancellationException("cancelled")
            coEvery { gitSyncRepository.sync() } throws cancellation

            try {
                useCase(forceSync = false)
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }

            coVerify(exactly = 1) { gitSyncRepository.sync() }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `force sync failure still refreshes and rethrows original git error`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
            val failure = IllegalStateException("sync failed")
            coEvery { gitSyncRepository.sync() } throws failure
            coEvery { memoRepository.refreshMemos() } returns Unit

            try {
                useCase(forceSync = true)
                fail("Expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertSame(failure, e)
            }

            coVerifyOrder {
                gitSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `force sync webdav result error still refreshes and throws mapped failure`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            coEvery { webDavSyncRepository.sync() } returns WebDavSyncResult.Error("sync failed")
            coEvery { memoRepository.refreshMemos() } returns Unit

            val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()
            assertTrue(thrown is Exception)
            assertEquals("sync failed", thrown?.message)

            coVerifyOrder {
                webDavSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `force sync result cancellation is rethrown for webdav`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            val cancellation = CancellationException("cancelled")
            coEvery {
                webDavSyncRepository.sync()
            } returns WebDavSyncResult.Error("cancelled", cancellation)
            coEvery { memoRepository.refreshMemos() } returns Unit

            try {
                useCase(forceSync = true)
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }

            coVerify(exactly = 1) { webDavSyncRepository.sync() }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `non-force sync failure remains best-effort and refresh still runs`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
            every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(true)
            coEvery { gitSyncRepository.sync() } throws IllegalArgumentException("sync failed")
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerifyOrder {
                gitSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `non-force webdav refresh skips remote sync when disabled`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(false)
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerify(exactly = 0) { webDavSyncRepository.sync() }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `force sync with no backend refreshes without remote sync`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = true)

            coVerify(exactly = 1) { memoRepository.refreshMemos() }
            coVerify(exactly = 0) { gitSyncRepository.sync() }
            coVerify(exactly = 0) { webDavSyncRepository.sync() }
            coVerify(exactly = 0) { s3SyncRepository.sync() }
        }

    @Test
    fun `force sync git direct path required refreshes then throws mapped failure`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
            coEvery { gitSyncRepository.sync() } returns GitSyncResult.DirectPathRequired
            coEvery { memoRepository.refreshMemos() } returns Unit

            val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

            assertTrue(thrown is GitSyncFailureException)
            assertEquals(GitSyncErrorCode.DIRECT_PATH_REQUIRED, (thrown as GitSyncFailureException).code)
            assertEquals("Git sync requires a direct local directory path", thrown.message)
            coVerifyOrder {
                gitSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `force sync git conflict refreshes then throws sync conflict exception`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
            val conflicts =
                SyncConflictSet(
                    source = SyncBackendType.GIT,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "2026_03_26.md",
                                localContent = "local",
                                remoteContent = "remote",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 123L,
                )
            coEvery {
                gitSyncRepository.sync()
            } returns GitSyncResult.Conflict(message = "conflict", conflicts = conflicts)
            coEvery { memoRepository.refreshMemos() } returns Unit

            val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

            assertTrue(thrown is SyncConflictException)
            assertEquals(conflicts, (thrown as SyncConflictException).conflicts)
            coVerifyOrder {
                gitSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `force sync webdav not configured refreshes then throws mapped failure`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            coEvery { webDavSyncRepository.sync() } returns WebDavSyncResult.NotConfigured
            coEvery { memoRepository.refreshMemos() } returns Unit

            val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

            assertTrue(thrown is WebDavSyncFailureException)
            assertEquals("WebDAV sync is not configured", thrown?.message)
            coVerifyOrder {
                webDavSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `non-force git refresh skips remote sync when git sync is disabled`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
            every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(false)
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerify(exactly = 0) { gitSyncRepository.sync() }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `force sync s3 not configured refreshes then throws mapped failure`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.S3)
            coEvery { s3SyncRepository.sync() } returns S3SyncResult.NotConfigured
            coEvery { memoRepository.refreshMemos() } returns Unit

            val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

            assertTrue(thrown is S3SyncFailureException)
            assertEquals(S3SyncErrorCode.NOT_CONFIGURED, (thrown as S3SyncFailureException).code)
            assertEquals("S3 sync is not configured", thrown.message)
            coVerifyOrder {
                s3SyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `non-force s3 refresh skips remote sync when s3 sync is disabled`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.S3)
            every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(false)
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerify(exactly = 0) { s3SyncRepository.sync() }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }

    @Test
    fun `non-force s3 refresh triggers optimized s3 sync when enabled`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.S3)
            every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(true)
            coEvery { s3SyncRepository.sync() } returns S3SyncResult.Success("S3 sync completed")
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerifyOrder {
                s3SyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }
}
