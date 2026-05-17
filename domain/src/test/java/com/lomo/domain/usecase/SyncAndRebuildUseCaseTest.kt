package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import io.kotest.assertions.fail
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Behavior focus: force vs best-effort sync, refresh ordering, and cancellation or error propagation.
 * - Observable outcomes: thrown exception type, refresh execution, and collaborator call ordering.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: repository internals, transport implementation details, and UI rendering.
 */
class SyncAndRebuildUseCaseTest : DomainFunSpec() {
    private val memoRepository: MemoRepository = mockk()
    private val gitSyncRepository: GitSyncRepository = mockk()
    private val webDavSyncRepository: WebDavSyncRepository = mockk()
    private val s3SyncRepository: S3SyncRepository = mockk()
    private val syncInboxRepository: SyncInboxRepository = mockk(relaxed = true)
    private val preferencesRepository: PreferencesRepository = mockk(relaxed = true)
    private val syncPolicyRepository: SyncPolicyRepository = mockk()
    private val syncProviderRegistry =
        SyncProviderRegistry(
            providers =
                listOf(
                    GitUnifiedSyncProvider(gitSyncRepository),
                    WebDavUnifiedSyncProvider(webDavSyncRepository),
                    S3UnifiedSyncProvider(s3SyncRepository),
                    InboxUnifiedSyncProvider(
                        syncInboxRepository = syncInboxRepository,
                        preferencesRepository = preferencesRepository,
                    ),
                ),
        )
    private val useCase =
        SyncAndRebuildUseCase(
            memoRepository = memoRepository,
            syncProviderRegistry = syncProviderRegistry,
            syncPolicyRepository = syncPolicyRepository,
        )
    init {
        beforeTest {
            every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
            coEvery {
                syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
            } returns UnifiedSyncResult.Success(
                provider = SyncBackendType.INBOX,
                message = "processed",
            )
        }
    }
    init {
        test("non-force git sync cancellation is rethrown and refresh is skipped") {
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
                            e shouldBe cancellation
                        }

                        coVerify(exactly = 1) { gitSyncRepository.sync() }
                        coVerify(exactly = 0) { memoRepository.refreshMemos() }
                    }
        }
    }
    init {
        test("force sync failure still refreshes and rethrows original git error") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
                        val failure = IllegalStateException("sync failed")
                        coEvery { gitSyncRepository.sync() } throws failure
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        try {
                            useCase(forceSync = true)
                            fail("Expected IllegalStateException")
                        } catch (e: IllegalStateException) {
                            e shouldBe failure
                        }

                        coVerifyOrder {
                            gitSyncRepository.sync()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
    init {
        test("force sync webdav result error still refreshes and throws mapped failure") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
                        coEvery { webDavSyncRepository.sync() } returns WebDavSyncResult.Error("sync failed")
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()
                        val failure = thrown.shouldBeInstanceOf<Exception>()
                        failure.message shouldBe "sync failed"

                        coVerifyOrder {
                            webDavSyncRepository.sync()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
    init {
        test("force sync result cancellation is rethrown for webdav") {
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
                            e shouldBe cancellation
                        }

                        coVerify(exactly = 1) { webDavSyncRepository.sync() }
                        coVerify(exactly = 0) { memoRepository.refreshMemos() }
                    }
        }
    }
    init {
        test("non-force sync failure remains best-effort and refresh still runs") {
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
        }
    }
    init {
        test("non-force webdav refresh skips remote sync when disabled") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
                        every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(false)
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        useCase(forceSync = false)

                        coVerify(exactly = 0) { webDavSyncRepository.sync() }
                        coVerify(exactly = 1) { memoRepository.refreshMemos() }
                    }
        }
    }
    init {
        test("force sync with no backend refreshes without remote sync") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        useCase(forceSync = true)

                        coVerify(exactly = 1) { memoRepository.refreshMemos() }
                        coVerify(exactly = 0) { gitSyncRepository.sync() }
                        coVerify(exactly = 0) { webDavSyncRepository.sync() }
                        coVerify(exactly = 0) { s3SyncRepository.sync() }
                    }
        }
    }
    init {
        test("force sync git direct path required refreshes then throws mapped failure") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
                        coEvery { gitSyncRepository.sync() } returns GitSyncResult.DirectPathRequired
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

                        val failure = thrown.shouldBeInstanceOf<GitSyncFailureException>()
                        failure.code shouldBe GitSyncErrorCode.DIRECT_PATH_REQUIRED
                        failure.message shouldBe "Git sync requires a direct local directory path"
                        coVerifyOrder {
                            gitSyncRepository.sync()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
    init {
        test("force sync git conflict refreshes then throws sync conflict exception") {
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

                        val failure = thrown.shouldBeInstanceOf<SyncConflictException>()
                        failure.conflicts shouldBe conflicts
                        coVerifyOrder {
                            gitSyncRepository.sync()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
    init {
        test("force sync webdav not configured refreshes then throws mapped failure") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
                        coEvery { webDavSyncRepository.sync() } returns WebDavSyncResult.NotConfigured
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

                        val failure = thrown.shouldBeInstanceOf<WebDavSyncFailureException>()
                        failure.message shouldBe "WebDAV sync is not configured"
                        coVerifyOrder {
                            webDavSyncRepository.sync()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
    init {
        test("non-force git refresh skips remote sync when git sync is disabled") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
                        every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(false)
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        useCase(forceSync = false)

                        coVerify(exactly = 0) { gitSyncRepository.sync() }
                        coVerify(exactly = 1) { memoRepository.refreshMemos() }
                    }
        }
    }

    /*
     * Test Change Justification:
     * - Reason category: product/domain contract changed
     * - Replaced assertion: force-refresh S3 branch previously verified `s3SyncRepository.syncForRefresh()`
     * - Previous assertion is no longer correct because `forceSync=true` is the manual-sync path and the unified provider routes manual S3 sync through `sync()`, while only refresh-triggered sync uses `syncForRefresh()`
     * - Retained coverage: still verifies refresh ordering, mapped not-configured failure, and that memo refresh happens before the exception is rethrown
     * - Why this is not changing the test to fit the implementation: the corrected assertion matches the domain contract split between manual sync and refresh sync
     */
    init {
        test("force sync s3 not configured refreshes then throws mapped failure") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.S3)
                        coEvery { s3SyncRepository.sync() } returns S3SyncResult.NotConfigured
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()

                        val failure = thrown.shouldBeInstanceOf<S3SyncFailureException>()
                        failure.code shouldBe S3SyncErrorCode.NOT_CONFIGURED
                        failure.message shouldBe "S3 sync is not configured"
                        coVerifyOrder {
                            s3SyncRepository.sync()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
    init {
        test("non-force s3 refresh skips remote sync when s3 sync is disabled") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.S3)
                        every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(false)
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        useCase(forceSync = false)

                        coVerify(exactly = 0) { s3SyncRepository.sync() }
                        coVerify(exactly = 1) { memoRepository.refreshMemos() }
                    }
        }
    }
    init {
        test("non-force s3 refresh triggers optimized s3 sync when enabled") {
            runTest {
                        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.S3)
                        every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(true)
                        coEvery { s3SyncRepository.syncForRefresh() } returns S3SyncResult.Success("S3 sync completed")
                        coEvery { memoRepository.refreshMemos() } returns Unit

                        useCase(forceSync = false)

                        coVerifyOrder {
                            s3SyncRepository.syncForRefresh()
                            memoRepository.refreshMemos()
                        }
                    }
        }
    }
}
