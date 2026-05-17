package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: WebDavSyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, auto-sync policy application, and action delegation behavior.
 * - Observable outcomes: backend policy writes, repository mutation invocations, and sync action delegation parameters.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: WebDAV transport behavior, repository implementation internals, and UI rendering.
 */
class WebDavSyncSettingsUseCaseTest : DomainFunSpec() {
    private val webDavSyncRepository: WebDavSyncRepository = mockk(relaxed = true)
    private val syncPolicyRepository: SyncPolicyRepository = mockk(relaxed = true)
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk(relaxed = true)

    private val useCase =
        WebDavSyncSettingsUseCase(
            webDavSyncRepository = webDavSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )
    init {
        test("updateWebDavSyncEnabled true applies WebDAV backend policy") {
            runTest {
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateWebDavSyncEnabled(enabled = true)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateWebDavSyncEnabled false applies None backend policy") {
            runTest {
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateWebDavSyncEnabled(enabled = false)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("webdav setting mutations delegate to repository") {
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
        }
    }
    init {
        test("updateAutoSyncEnabled writes flag and reapplies policy") {
            runTest {
                        coEvery { webDavSyncRepository.setAutoSyncEnabled(true) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateAutoSyncEnabled(enabled = true)

                        coVerifyOrder {
                            webDavSyncRepository.setAutoSyncEnabled(true)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateAutoSyncInterval writes interval and reapplies policy") {
            runTest {
                        coEvery { webDavSyncRepository.setAutoSyncInterval("1h") } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateAutoSyncInterval(interval = "1h")

                        coVerifyOrder {
                            webDavSyncRepository.setAutoSyncInterval("1h")
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateSyncOnRefreshEnabled only writes repository flag") {
            runTest {
                        coEvery { webDavSyncRepository.setSyncOnRefreshEnabled(true) } returns Unit

                        useCase.updateSyncOnRefreshEnabled(enabled = true)

                        coVerify(exactly = 1) { webDavSyncRepository.setSyncOnRefreshEnabled(true) }
                        coVerify(exactly = 0) { syncPolicyRepository.applyRemoteSyncPolicy() }
                    }
        }
    }
    init {
        test("isPasswordConfigured delegates to repository") {
            runTest {
                        coEvery { webDavSyncRepository.isPasswordConfigured() } returns true

                        val result = useCase.isPasswordConfigured()

                        (result) shouldBe true
                        coVerify(exactly = 1) { webDavSyncRepository.isPasswordConfigured() }
                    }
        }
    }
    init {
        test("triggerSyncNow delegates with forceSync true") {
            runTest {
                        coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } returns Unit

                        useCase.triggerSyncNow()

                        coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(forceSync = true) }
                    }
        }
    }
    init {
        test("testConnection delegates to webdav repository") {
            runTest {
                        val expected = WebDavSyncResult.Success("connected")
                        coEvery { webDavSyncRepository.testConnection() } returns expected

                        val result = useCase.testConnection()

                        result shouldBe expected
                        coVerify(exactly = 1) { webDavSyncRepository.testConnection() }
                    }
        }
    }
    init {
        test("state observation delegates expose repository flows") {
            runTest {
                        every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(true)
                        every { webDavSyncRepository.getProvider() } returns flowOf(WebDavProvider.NEXTCLOUD)
                        every { webDavSyncRepository.getBaseUrl() } returns flowOf("https://dav.example.com")
                        every { webDavSyncRepository.getEndpointUrl() } returns flowOf("https://dav.example.com/files")
                        every { webDavSyncRepository.getUsername() } returns flowOf("alice")
                        every { webDavSyncRepository.getAutoSyncEnabled() } returns flowOf(true)
                        every { webDavSyncRepository.getAutoSyncInterval() } returns flowOf("2h")
                        every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { webDavSyncRepository.observeLastSyncTimeMillis() } returns flowOf(5678L)
                        every { webDavSyncRepository.syncState() } returns flowOf(com.lomo.domain.model.WebDavSyncState.Downloading)

                        useCase.observeWebDavSyncEnabled().first() shouldBe true
                        useCase.observeProvider().first() shouldBe WebDavProvider.NEXTCLOUD
                        useCase.observeBaseUrl().first() shouldBe "https://dav.example.com"
                        useCase.observeEndpointUrl().first() shouldBe "https://dav.example.com/files"
                        useCase.observeUsername().first() shouldBe "alice"
                        useCase.observeAutoSyncEnabled().first() shouldBe true
                        useCase.observeAutoSyncInterval().first() shouldBe "2h"
                        useCase.observeSyncOnRefreshEnabled().first() shouldBe true
                        useCase.observeLastSyncTimeMillis().first() shouldBe 5678L
                        useCase.observeSyncState().first() shouldBe com.lomo.domain.model.WebDavSyncState.Downloading
                    }
        }
    }
}
