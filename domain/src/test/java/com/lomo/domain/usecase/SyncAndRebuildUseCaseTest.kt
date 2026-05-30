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
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakePreferencesRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import com.lomo.domain.testing.fakes.FakeGitSyncRepository
import com.lomo.domain.testing.fakes.FakeWebDavSyncRepository
import com.lomo.domain.testing.fakes.FakeS3SyncRepository
import com.lomo.domain.testing.fakes.FakeSyncInboxRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Capability: Sync orchestration and coordination across Git, WebDAV, S3, and Inbox sync backends.
 * - Scenarios:
 *   - Given a configured Git backend, when running non-force sync with cancellation, then it is rethrown and refresh is skipped.
 *   - Given a configured Git backend, when running force sync with failure, then it refreshes and rethrows the failure.
 *   - Given a configured WebDAV backend, when running force sync with error, then it refreshes and throws the mapped WebDAV exception.
 *   - Given a configured WebDAV backend, when running force sync with cancellation, then it is rethrown and refresh is skipped.
 *   - Given a configured Git backend, when running non-force sync with failure, then it fails silently best-effort and refreshes anyway.
 *   - Given a configured WebDAV backend, when running non-force sync and WebDAV is disabled, then it skips remote sync and refreshes.
 *   - Given no backend, when running force sync, then it refreshes without triggering any remote sync.
 *   - Given a configured Git backend requiring direct path, when running force sync, then it refreshes and throws direct path required error.
 *   - Given a configured Git backend with conflicts, when running force sync, then it refreshes and throws SyncConflictException.
 *   - Given an unconfigured WebDAV backend, when running force sync, then it refreshes and throws not-configured error.
 *   - Given a configured Git backend, when running non-force sync and Git sync is disabled, then it skips remote sync and refreshes.
 *   - Given an unconfigured S3 backend, when running force sync, then it refreshes and throws S3 not-configured error.
 *   - Given a configured S3 backend, when running non-force sync and S3 sync is disabled, then it skips remote sync and refreshes.
 *   - Given a configured S3 backend, when running non-force sync and S3 sync is enabled, then it triggers optimized S3 sync and refreshes.
 * - Observable outcomes: thrown exception type, refresh execution, and collaborator call counts / state mutations.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository transport internals, remote network request details, and UI rendering.
 */
class SyncAndRebuildUseCaseTest : DomainFunSpec() {
    private val memoRepository = FakeMemoStore()
    private val gitSyncRepository = FakeGitSyncRepository()
    private val webDavSyncRepository = FakeWebDavSyncRepository()
    private val s3SyncRepository = FakeS3SyncRepository()
    private val syncInboxRepository = FakeSyncInboxRepository()
    private val preferencesRepository = FakePreferencesRepository()
    private val syncPolicyRepository = FakeSyncPolicyRepository()

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
            memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
            syncProviderRegistry = syncProviderRegistry,
            syncPolicyRepository = syncPolicyRepository,
        )

    /*
     * Test Change Justification:
     * - Reason category: test framework migration & optimization
     * - Replaced assertion: Removed all coVerify/verify MockK checks.
     * - Previous assertion is no longer correct because we migrated completely to pure state-based fakes.
     * - Retained coverage: All BDD scenarios and assertions on observable side-effects are completely preserved.
     * - Why this is not changing the test to fit the implementation: The contract of SyncAndRebuildUseCase remains exactly as specified.
     */
    init {
        test("non-force git sync cancellation is rethrown and refresh is skipped") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                gitSyncRepository.setSyncOnRefreshEnabledValue(true)
                gitSyncRepository.setEnabled(true)
                val cancellation = CancellationException("cancelled")
                gitSyncRepository.syncFailure = cancellation

                val exception = shouldThrow<CancellationException> {
                    useCase(forceSync = false)
                }
                exception shouldBe cancellation
                gitSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("force sync failure still refreshes and rethrows original git error") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                val failure = IllegalStateException("sync failed")
                gitSyncRepository.syncFailure = failure

                val exception = shouldThrow<IllegalStateException> {
                    useCase(forceSync = true)
                }
                exception shouldBe failure
                gitSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("force sync webdav result error still refreshes and throws mapped failure") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                webDavSyncRepository.nextSyncResult = WebDavSyncResult.Error("sync failed")

                val exception = shouldThrow<Exception> {
                    useCase(forceSync = true)
                }
                exception.message shouldBe "sync failed"
                webDavSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("force sync result cancellation is rethrown for webdav") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                val cancellation = CancellationException("cancelled")
                webDavSyncRepository.nextSyncResult = WebDavSyncResult.Error("cancelled", cancellation)

                val exception = shouldThrow<CancellationException> {
                    useCase(forceSync = true)
                }
                exception shouldBe cancellation
                webDavSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("non-force sync failure remains best-effort and refresh still runs") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                gitSyncRepository.setSyncOnRefreshEnabledValue(true)
                gitSyncRepository.setEnabled(true)
                gitSyncRepository.syncFailure = IllegalArgumentException("sync failed")

                useCase(forceSync = false)

                gitSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("non-force webdav refresh skips remote sync when disabled") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                webDavSyncRepository.setSyncOnRefreshEnabledValue(true)
                webDavSyncRepository.setEnabled(false)

                useCase(forceSync = false)

                webDavSyncRepository.syncCallCount shouldBe 0
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("force sync with no backend refreshes without remote sync") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)

                useCase(forceSync = true)

                memoRepository.refreshMemosCallCount shouldBe 1
                gitSyncRepository.syncCallCount shouldBe 0
                webDavSyncRepository.syncCallCount shouldBe 0
            }
        }

        test("force sync git direct path required refreshes then throws mapped failure") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                gitSyncRepository.nextSyncResult = GitSyncResult.DirectPathRequired

                val exception = shouldThrow<GitSyncFailureException> {
                    useCase(forceSync = true)
                }
                exception.code shouldBe GitSyncErrorCode.DIRECT_PATH_REQUIRED
                exception.message shouldBe "Git sync requires a direct local directory path"
                gitSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("force sync git conflict refreshes then throws sync conflict exception") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
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
                gitSyncRepository.nextSyncResult = GitSyncResult.Conflict(message = "conflict", conflicts = conflicts)

                val exception = shouldThrow<SyncConflictException> {
                    useCase(forceSync = true)
                }
                exception.conflicts shouldBe conflicts
                gitSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("force sync webdav not configured refreshes then throws mapped failure") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                webDavSyncRepository.nextSyncResult = WebDavSyncResult.NotConfigured

                val exception = shouldThrow<WebDavSyncFailureException> {
                    useCase(forceSync = true)
                }
                exception.message shouldBe "WebDAV sync is not configured"
                webDavSyncRepository.syncCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("non-force git refresh skips remote sync when git sync is disabled") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                gitSyncRepository.setSyncOnRefreshEnabledValue(true)
                gitSyncRepository.setEnabled(false)

                useCase(forceSync = false)

                gitSyncRepository.syncCallCount shouldBe 0
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("force sync s3 not configured refreshes then throws mapped failure") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.S3)
                s3SyncRepository.nextSyncResult = S3SyncResult.NotConfigured

                val exception = shouldThrow<S3SyncFailureException> {
                    useCase(forceSync = true)
                }
                exception.code shouldBe S3SyncErrorCode.NOT_CONFIGURED
                exception.message shouldBe "S3 sync is not configured"
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("non-force s3 refresh skips remote sync when s3 sync is disabled") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.S3)
                s3SyncRepository.setSyncOnRefreshEnabledValue(true)
                s3SyncRepository.setEnabled(false)

                useCase(forceSync = false)

                s3SyncRepository.syncCallCount shouldBe 0
                s3SyncRepository.syncForRefreshCallCount shouldBe 0
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("non-force s3 refresh triggers optimized s3 sync when enabled") {
            runTest {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.S3)
                s3SyncRepository.setSyncOnRefreshEnabledValue(true)
                s3SyncRepository.setEnabled(true)
                s3SyncRepository.nextSyncResult = S3SyncResult.Success("S3 sync completed")

                useCase(forceSync = false)

                s3SyncRepository.syncForRefreshCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }
    }
}
