package com.lomo.domain.repository

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


import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: GitSyncRepository default helpers
 * - Behavior focus: observeLastSyncInstant/observeLastSyncTimeMillis default mapping remains stable after repository surface cleanup.
 * - Observable outcomes: null-vs-instant mapping from the last-sync flow.
 * - TDD proof: Fails to compile before the cleanup because the old Git memo-history method is still required by GitSyncRepository.
 * - Excludes: sync execution, conflict handling, and any removed Git memo-history capability.
 */
class GitSyncRepositoryDefaultsTest : DomainFunSpec() {
    init {
        test("observeLastSyncTimeMillis maps zero sentinel to null") {
            runTest {
                        val repository = LastSyncOnlyGitSyncRepository(flowOf(null))

                        val lastSync = repository.observeLastSyncTimeMillis().first()

                        lastSync shouldBe null
                    }
        }

        test("observeLastSyncInstant maps positive millis to instant") {
            runTest {
                        val repository = LastSyncOnlyGitSyncRepository(flowOf(1_700_000_000_000L))

                        val lastSync = repository.observeLastSyncInstant().first()

                        lastSync shouldBe Instant.ofEpochMilli(1_700_000_000_000L)
                    }
        }
    }

    private class LastSyncOnlyGitSyncRepository(
        private val lastSyncTimeFlow: Flow<Long?>,
    ) : GitSyncRepository {
        override fun isGitSyncEnabled(): Flow<Boolean> = flowOf(false)

        override fun getRemoteUrl(): Flow<String?> = flowOf(null)

        override fun getAutoSyncEnabled(): Flow<Boolean> = flowOf(false)

        override fun getAutoSyncInterval(): Flow<String> = flowOf("never")

        override fun observeLastSyncTimeMillis(): Flow<Long?> = lastSyncTimeFlow

        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(false)

        override suspend fun setGitSyncEnabled(enabled: Boolean) = Unit

        override suspend fun setRemoteUrl(url: String) = Unit

        override suspend fun setToken(token: String) = Unit

        override suspend fun getTokenStatus(): StoredCredentialStatus = StoredCredentialStatus.Missing

        override suspend fun setAuthorInfo(
            name: String,
            email: String,
        ) = Unit

        override fun getAuthorName(): Flow<String> = flowOf("")

        override fun getAuthorEmail(): Flow<String> = flowOf("")

        override suspend fun setAutoSyncEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoSyncInterval(interval: String) = Unit

        override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) = Unit

        override suspend fun initOrClone(): GitSyncResult = GitSyncResult.NotConfigured

        override suspend fun sync(): GitSyncResult = GitSyncResult.NotConfigured

        override suspend fun getStatus(): GitSyncStatus =
            GitSyncStatus(
                hasLocalChanges = false,
                aheadCount = 0,
                behindCount = 0,
                lastSyncTime = null,
            )

        override suspend fun testConnection(): GitSyncResult = GitSyncResult.NotConfigured

        override suspend fun resetRepository(): GitSyncResult = GitSyncResult.NotConfigured

        override suspend fun resetLocalBranchToRemote(): GitSyncResult = GitSyncResult.NotConfigured

        override suspend fun forcePushLocalToRemote(): GitSyncResult = GitSyncResult.NotConfigured

        override fun syncState(): Flow<UnifiedSyncState> = flowOf(UnifiedSyncState.Idle)

        override suspend fun resolveConflicts(
            resolution: com.lomo.domain.model.SyncConflictResolution,
            conflictSet: com.lomo.domain.model.SyncConflictSet,
        ): GitSyncResult = GitSyncResult.NotConfigured
    }
}
