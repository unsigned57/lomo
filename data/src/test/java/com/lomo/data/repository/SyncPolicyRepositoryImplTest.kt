package com.lomo.data.repository

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.worker.GitSyncScheduler
import com.lomo.data.worker.S3SyncScheduler
import com.lomo.data.worker.WebDavSyncScheduler
import com.lomo.domain.model.SyncBackendType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SyncPolicyRepositoryImpl
 * - Behavior focus: backend preference mapping and scheduler enable/cancel policy.
 * - Observable outcomes: observed SyncBackendType values, atomic datastore flag mutation, and scheduler calls.
 * - Red phase: Fails before the fix because the S3 backend cannot be persisted or rescheduled, so remote sync policy never activates the S3 scheduler.
 * - Excludes: WorkManager request construction and Android context-driven worker registration.
 */
class SyncPolicyRepositoryImplTest {
    private val context: Context = mockk(relaxed = true)
    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val gitSyncScheduler: GitSyncScheduler = mockk(relaxed = true)
    private val webDavSyncScheduler: WebDavSyncScheduler = mockk(relaxed = true)
    private val s3SyncScheduler: S3SyncScheduler = mockk(relaxed = true)

    private val repository =
        SyncPolicyRepositoryImpl(
            context = context,
            dataStore = dataStore,
            gitSyncScheduler = gitSyncScheduler,
            webDavSyncScheduler = webDavSyncScheduler,
            s3SyncScheduler = s3SyncScheduler,
        )

    @Test
    fun `observeRemoteSyncBackend maps persisted preference and falls back to NONE`() =
        runTest {
            every { dataStore.syncBackendType } returns flowOf("unexpected")

            assertEquals(SyncBackendType.NONE, repository.observeRemoteSyncBackend().first())
        }

    @Test
    fun `setRemoteSyncBackend writes all four keys atomically for git backend`() =
        runTest {
            repository.setRemoteSyncBackend(SyncBackendType.GIT)

            coVerify(exactly = 1) { dataStore.setRemoteSyncBackendFlags("git", true, false, false) }
            coVerify(exactly = 0) { dataStore.updateSyncBackendType(any()) }
            coVerify(exactly = 0) { dataStore.updateGitSyncEnabled(any()) }
            coVerify(exactly = 0) { dataStore.updateWebDavSyncEnabled(any()) }
            coVerify(exactly = 0) { dataStore.updateS3SyncEnabled(any()) }
        }

    @Test
    fun `setRemoteSyncBackend writes all four keys atomically for NONE backend`() =
        runTest {
            repository.setRemoteSyncBackend(SyncBackendType.NONE)

            coVerify(exactly = 1) { dataStore.setRemoteSyncBackendFlags("none", false, false, false) }
            coVerify(exactly = 0) { dataStore.updateSyncBackendType(any()) }
            coVerify(exactly = 0) { dataStore.updateGitSyncEnabled(any()) }
        }

    @Test
    fun `setRemoteSyncBackend writes all four keys atomically for s3 backend`() =
        runTest {
            repository.setRemoteSyncBackend(SyncBackendType.S3)

            coVerify(exactly = 1) { dataStore.setRemoteSyncBackendFlags("s3", false, false, true) }
            coVerify(exactly = 0) { dataStore.updateSyncBackendType(any()) }
            coVerify(exactly = 0) { dataStore.updateS3SyncEnabled(any()) }
        }

    @Test
    fun `applyRemoteSyncPolicy cancels both schedulers when backend is NONE`() =
        runTest {
            every { dataStore.syncBackendType } returns flowOf("none")

            repository.applyRemoteSyncPolicy()

            verify(exactly = 1) { gitSyncScheduler.cancel() }
            verify(exactly = 1) { webDavSyncScheduler.cancel() }
            verify(exactly = 1) { s3SyncScheduler.cancel() }
            coVerify(exactly = 0) { gitSyncScheduler.reschedule() }
            coVerify(exactly = 0) { webDavSyncScheduler.reschedule() }
            coVerify(exactly = 0) { s3SyncScheduler.reschedule() }
        }

    @Test
    fun `applyRemoteSyncPolicy reschedules git and cancels webdav when backend is GIT`() =
        runTest {
            every { dataStore.syncBackendType } returns flowOf("git")
            coEvery { gitSyncScheduler.reschedule() } returns Unit

            repository.applyRemoteSyncPolicy()

            verify(exactly = 1) { webDavSyncScheduler.cancel() }
            verify(exactly = 1) { s3SyncScheduler.cancel() }
            coVerify(exactly = 1) { gitSyncScheduler.reschedule() }
            coVerify(exactly = 0) { webDavSyncScheduler.reschedule() }
            coVerify(exactly = 0) { s3SyncScheduler.reschedule() }
        }

    @Test
    fun `applyRemoteSyncPolicy reschedules webdav and cancels git when backend is WEBDAV`() =
        runTest {
            every { dataStore.syncBackendType } returns flowOf("webdav")
            coEvery { webDavSyncScheduler.reschedule() } returns Unit

            repository.applyRemoteSyncPolicy()

            verify(exactly = 1) { gitSyncScheduler.cancel() }
            verify(exactly = 1) { s3SyncScheduler.cancel() }
            coVerify(exactly = 1) { webDavSyncScheduler.reschedule() }
            coVerify(exactly = 0) { gitSyncScheduler.reschedule() }
        }

    @Test
    fun `applyRemoteSyncPolicy reschedules s3 and cancels git webdav when backend is S3`() =
        runTest {
            every { dataStore.syncBackendType } returns flowOf("s3")
            coEvery { s3SyncScheduler.reschedule() } returns Unit

            repository.applyRemoteSyncPolicy()

            verify(exactly = 1) { gitSyncScheduler.cancel() }
            verify(exactly = 1) { webDavSyncScheduler.cancel() }
            coVerify(exactly = 1) { s3SyncScheduler.reschedule() }
            coVerify(exactly = 0) { gitSyncScheduler.reschedule() }
            coVerify(exactly = 0) { webDavSyncScheduler.reschedule() }
        }
}
