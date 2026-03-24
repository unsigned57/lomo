package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: WebDavSyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, auto-sync policy application, and action delegation behavior.
 * - Observable outcomes: backend policy writes, repository mutation invocations, and sync action delegation parameters.
 * - Excludes: WebDAV transport behavior, repository implementation internals, and UI rendering.
 */
class WebDavSyncSettingsUseCaseTest {
    private val webDavSyncRepository: WebDavSyncRepository = mockk(relaxed = true)
    private val syncPolicyRepository: SyncPolicyRepository = mockk(relaxed = true)
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk(relaxed = true)

    private val useCase =
        WebDavSyncSettingsUseCase(
            webDavSyncRepository = webDavSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

    @Test
    fun `updateWebDavSyncEnabled true applies WebDAV backend policy`() =
        runTest {
            coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV) } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateWebDavSyncEnabled(enabled = true)

            coVerifyOrder {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateWebDavSyncEnabled false applies None backend policy`() =
        runTest {
            coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE) } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateWebDavSyncEnabled(enabled = false)

            coVerifyOrder {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `webdav setting mutations delegate to repository`() =
        runTest {
            coEvery { webDavSyncRepository.setProvider(WebDavProvider.NEXTCLOUD) } returns Unit
            coEvery { webDavSyncRepository.setBaseUrl("https://dav.example.com") } returns Unit
            coEvery { webDavSyncRepository.setEndpointUrl("https://dav.example.com/endpoint") } returns Unit
            coEvery { webDavSyncRepository.setUsername("alice") } returns Unit
            coEvery { webDavSyncRepository.setPassword("secret") } returns Unit

            useCase.updateProvider(WebDavProvider.NEXTCLOUD)
            useCase.updateBaseUrl("https://dav.example.com")
            useCase.updateEndpointUrl("https://dav.example.com/endpoint")
            useCase.updateUsername("alice")
            useCase.updatePassword("secret")

            coVerify(exactly = 1) { webDavSyncRepository.setProvider(WebDavProvider.NEXTCLOUD) }
            coVerify(exactly = 1) { webDavSyncRepository.setBaseUrl("https://dav.example.com") }
            coVerify(exactly = 1) { webDavSyncRepository.setEndpointUrl("https://dav.example.com/endpoint") }
            coVerify(exactly = 1) { webDavSyncRepository.setUsername("alice") }
            coVerify(exactly = 1) { webDavSyncRepository.setPassword("secret") }
        }

    @Test
    fun `updateAutoSyncEnabled writes flag and reapplies policy`() =
        runTest {
            coEvery { webDavSyncRepository.setAutoSyncEnabled(true) } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateAutoSyncEnabled(enabled = true)

            coVerifyOrder {
                webDavSyncRepository.setAutoSyncEnabled(true)
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateAutoSyncInterval writes interval and reapplies policy`() =
        runTest {
            coEvery { webDavSyncRepository.setAutoSyncInterval("1h") } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateAutoSyncInterval(interval = "1h")

            coVerifyOrder {
                webDavSyncRepository.setAutoSyncInterval("1h")
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateSyncOnRefreshEnabled only writes repository flag`() =
        runTest {
            coEvery { webDavSyncRepository.setSyncOnRefreshEnabled(true) } returns Unit

            useCase.updateSyncOnRefreshEnabled(enabled = true)

            coVerify(exactly = 1) { webDavSyncRepository.setSyncOnRefreshEnabled(true) }
            coVerify(exactly = 0) { syncPolicyRepository.applyRemoteSyncPolicy() }
        }

    @Test
    fun `isPasswordConfigured delegates to repository`() =
        runTest {
            coEvery { webDavSyncRepository.isPasswordConfigured() } returns true

            val result = useCase.isPasswordConfigured()

            assertTrue(result)
            coVerify(exactly = 1) { webDavSyncRepository.isPasswordConfigured() }
        }

    @Test
    fun `triggerSyncNow delegates with forceSync true`() =
        runTest {
            coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } returns Unit

            useCase.triggerSyncNow()

            coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(forceSync = true) }
        }

    @Test
    fun `testConnection delegates to webdav repository`() =
        runTest {
            val expected = WebDavSyncResult.Success("connected")
            coEvery { webDavSyncRepository.testConnection() } returns expected

            val result = useCase.testConnection()

            assertEquals(expected, result)
            coVerify(exactly = 1) { webDavSyncRepository.testConnection() }
        }
}
