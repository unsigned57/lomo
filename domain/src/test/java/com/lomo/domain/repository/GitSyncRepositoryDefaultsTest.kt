package com.lomo.domain.repository

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.model.SyncEngineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GitSyncRepositoryDefaultsTest {
    @Test
    fun `observeLastSyncTimeMillis maps zero sentinel to null`() =
        runTest {
            val repository = LastSyncOnlyGitSyncRepository(flowOf(null))

            val lastSync = repository.observeLastSyncTimeMillis().first()

            assertNull(lastSync)
        }

    @Test
    fun `observeLastSyncInstant maps positive millis to instant`() =
        runTest {
            val repository = LastSyncOnlyGitSyncRepository(flowOf(1_700_000_000_000L))

            val lastSync = repository.observeLastSyncInstant().first()

            assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), lastSync)
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

        override suspend fun getToken(): String? = null

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

        override suspend fun getMemoVersionHistory(
            dateKey: String,
            memoTimestamp: Long,
        ): List<MemoVersion> = emptyList()

        override fun syncState(): Flow<SyncEngineState> = flowOf(SyncEngineState.Idle)
    }
}
