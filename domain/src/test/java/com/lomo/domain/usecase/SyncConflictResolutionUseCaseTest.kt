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
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoRepository
import com.lomo.domain.testing.fakes.FakePreferencesRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import com.lomo.domain.testing.fakes.FakeGitSyncRepository
import com.lomo.domain.testing.fakes.FakeWebDavSyncRepository
import com.lomo.domain.testing.fakes.FakeS3SyncRepository
import com.lomo.domain.testing.fakes.FakeSyncInboxRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SyncConflictResolutionUseCase
 * - Capability: Unified conflict resolution and side-effect coordination across distinct remote sync backends.
 * - Scenarios:
 *   - Given a GIT conflict set, when resolved successfully, then it invokes resolve on the GIT repository and refreshes memos.
 *   - Given a GIT conflict set, when resolve fails with an error, then it maps the error to GitSyncFailureException and skips refresh.
 *   - Given a WEBDAV conflict set, when resolved successfully, then it invokes resolve on the WEBDAV repository and refreshes memos.
 *   - Given a WEBDAV conflict set, when resolve fails with an error, then it maps the error to WebDavSyncFailureException and skips refresh.
 *   - Given an S3 conflict set, when resolved successfully, then it invokes resolve on the S3 repository and refreshes memos.
 *   - Given an S3 conflict set, when resolve returns pending, then it returns a pending resolution result and skips refresh.
 *   - Given an S3 conflict set, when resolve fails with an error, then it maps the error to S3SyncFailureException and skips refresh.
 *   - Given a NONE conflict set, when resolved, then it skips remote resolver invocation and refreshes memos directly.
 *   - Given an INBOX conflict set, when resolved successfully, then it invokes resolve on the INBOX repository and refreshes memos.
 * - Observable outcomes: thrown domain exception type and payload, resolver invocation path, and refresh invocation count.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository implementation internals, network/filesystem behavior, and UI rendering.
 */
class SyncConflictResolutionUseCaseTest : DomainFunSpec() {
    private val gitSyncRepository = FakeGitSyncRepository()
    private val webDavSyncRepository = FakeWebDavSyncRepository()
    private val s3SyncRepository = FakeS3SyncRepository()
    private val syncInboxRepository = FakeSyncInboxRepository()
    private val preferencesRepository = FakePreferencesRepository()
    private val memoRepository = FakeMemoRepository()

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
        SyncConflictResolutionUseCase(
            syncProviderRegistry = syncProviderRegistry,
            memoRepository = memoRepository,
        )

    /*
     * Test Change Justification:
     * - Reason category: test framework migration & optimization
     * - Replaced assertion: Removed all coVerify/verify MockK checks.
     * - Previous assertion is no longer correct because we migrated completely to pure state-based fakes.
     * - Retained coverage: All BDD scenarios and assertions on observable side-effects are completely preserved.
     * - Why this is not changing the test to fit the implementation: The contract of SyncConflictResolutionUseCase remains exactly as specified.
     */
    init {
        test("git source resolves conflicts and refreshes memos on success") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.GIT)
                val resolution = sampleResolution()
                gitSyncRepository.nextResolveConflictsResult = GitSyncResult.Success("resolved")

                useCase.resolve(conflictSet, resolution)

                gitSyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                webDavSyncRepository.resolveRequests.size shouldBe 0
                s3SyncRepository.resolveRequests.size shouldBe 0
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("git source maps git error to failure exception and skips refresh") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.GIT)
                val resolution = sampleResolution()
                val cause = IllegalStateException("git failed")
                gitSyncRepository.nextResolveConflictsResult =
                    GitSyncResult.Error(code = GitSyncErrorCode.CONFLICT, message = "conflict", exception = cause)

                val thrown = shouldThrow<GitSyncFailureException> {
                    useCase.resolve(conflictSet, resolution)
                }

                thrown.code shouldBe GitSyncErrorCode.CONFLICT
                thrown.message shouldBe "conflict"
                thrown.cause shouldBe cause
                gitSyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("webdav source resolves conflicts and refreshes memos on success") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.WEBDAV)
                val resolution = sampleResolution()
                webDavSyncRepository.nextResolveConflictsResult = WebDavSyncResult.Success("resolved")

                useCase.resolve(conflictSet, resolution)

                gitSyncRepository.resolveRequests.size shouldBe 0
                webDavSyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                s3SyncRepository.resolveRequests.size shouldBe 0
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("webdav source maps webdav error to failure exception and skips refresh") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.WEBDAV)
                val resolution = sampleResolution()
                val cause = IllegalArgumentException("webdav failed")
                webDavSyncRepository.nextResolveConflictsResult =
                    WebDavSyncResult.Error(code = WebDavSyncErrorCode.CONNECTION_FAILED, message = "connection failed", exception = cause)

                val thrown = shouldThrow<WebDavSyncFailureException> {
                    useCase.resolve(conflictSet, resolution)
                }

                thrown.code shouldBe WebDavSyncErrorCode.CONNECTION_FAILED
                thrown.message shouldBe "connection failed"
                thrown.cause shouldBe cause
                webDavSyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("s3 source resolves conflicts and refreshes memos on success") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.S3)
                val resolution = sampleResolution()
                s3SyncRepository.nextResolveConflictsResult = S3SyncResult.Success("resolved")

                useCase.resolve(conflictSet, resolution)

                gitSyncRepository.resolveRequests.size shouldBe 0
                webDavSyncRepository.resolveRequests.size shouldBe 0
                s3SyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("s3 source returns pending result without refreshing memos") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.S3)
                val resolution = sampleResolution()
                val pending = conflictSet.copy(files = emptyList())
                s3SyncRepository.nextResolveConflictsResult =
                    S3SyncResult.Conflict(message = "Pending conflicts remain", conflicts = pending)

                val result = useCase.resolve(conflictSet, resolution)

                result shouldBe SyncConflictResolutionResult.Pending(pending)
                s3SyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("s3 source maps s3 error to failure exception and skips refresh") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.S3)
                val resolution = sampleResolution()
                val cause = IllegalStateException("s3 failed")
                s3SyncRepository.nextResolveConflictsResult =
                    S3SyncResult.Error(code = S3SyncErrorCode.BUCKET_ACCESS_FAILED, message = "bucket failed", exception = cause)

                val thrown = shouldThrow<S3SyncFailureException> {
                    useCase.resolve(conflictSet, resolution)
                }

                thrown.code shouldBe S3SyncErrorCode.BUCKET_ACCESS_FAILED
                thrown.message shouldBe "bucket failed"
                thrown.cause shouldBe cause
                s3SyncRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("none source skips resolver and still refreshes memos") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.NONE)
                val resolution = sampleResolution()

                useCase.resolve(conflictSet, resolution)

                gitSyncRepository.resolveRequests.size shouldBe 0
                webDavSyncRepository.resolveRequests.size shouldBe 0
                s3SyncRepository.resolveRequests.size shouldBe 0
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("inbox source resolves conflicts and refreshes memos on success") {
            runTest {
                val conflictSet = conflictSet(SyncBackendType.INBOX)
                val resolution = sampleResolution()
                preferencesRepository.setSyncInboxEnabled(true)
                syncInboxRepository.nextResolveResult = UnifiedSyncResult.Success(
                    provider = SyncBackendType.INBOX,
                    message = "resolved",
                )

                useCase.resolve(conflictSet, resolution)

                gitSyncRepository.resolveRequests.size shouldBe 0
                webDavSyncRepository.resolveRequests.size shouldBe 0
                s3SyncRepository.resolveRequests.size shouldBe 0
                syncInboxRepository.resolveRequests shouldBe listOf(resolution to conflictSet)
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }
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
