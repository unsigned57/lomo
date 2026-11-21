package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoMutationRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import com.lomo.domain.testing.fakes.FakeWebDavSyncRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class WebDavSyncSettingsUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private lateinit var webDavSyncRepository: FakeWebDavSyncRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var useCase: WebDavSyncSettingsUseCase

    init {
        beforeTest {
            eventLog.clear()
            webDavSyncRepository = FakeWebDavSyncRepository()
            syncPolicyRepository = FakeSyncPolicyRepository(eventLog = eventLog)
            useCase =
                WebDavSyncSettingsUseCase(
                    webDavSyncRepository = webDavSyncRepository,
                    syncPolicyRepository = syncPolicyRepository,
                    syncAndRebuildUseCase =
                    SyncAndRebuildUseCase(
                        memoRepository = FakeMemoMutationRepository(FakeMemoStore()),
                        syncProviderRegistry = SyncProviderRegistry(emptySet()),
                        syncPolicyRepository = syncPolicyRepository,
                    ),
                )
        }

        test("updateWebDavSyncEnabled applies backend policy") {
            runTest {
                useCase.updateWebDavSyncEnabled(enabled = true)
                useCase.updateWebDavSyncEnabled(enabled = false)

                syncPolicyRepository.setBackendRequests shouldBe
                    listOf(SyncBackendType.WEBDAV, SyncBackendType.NONE)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
                eventLog shouldBe
                    listOf(
                        "syncPolicy.setRemoteSyncBackend:WEBDAV",
                        "syncPolicy.applyRemoteSyncPolicy",
                        "syncPolicy.setRemoteSyncBackend:NONE",
                        "syncPolicy.applyRemoteSyncPolicy",
                    )
            }
        }

        test("webdav setting mutations update fake repository state") {
            runTest {
                useCase.updateProvider(WebDavProvider.NEXTCLOUD)
                useCase.updateBaseUrl("https://dav.example.com")
                useCase.updateEndpointUrl("https://dav.example.com/endpoint")
                useCase.updateUsername("alice")
                useCase.updatePassword("secret")

                webDavSyncRepository.providerWrites shouldBe listOf(WebDavProvider.NEXTCLOUD)
                webDavSyncRepository.baseUrlWrites shouldBe listOf("https://dav.example.com")
                webDavSyncRepository.endpointUrlWrites shouldBe listOf("https://dav.example.com/endpoint")
                webDavSyncRepository.usernameWrites shouldBe listOf("alice")
                webDavSyncRepository.passwordWrites shouldBe listOf("secret")
                useCase.isPasswordConfigured() shouldBe true
            }
        }

        test("auto sync mutations reapply policy while sync-on-refresh only writes flag") {
            runTest {
                useCase.updateAutoSyncEnabled(enabled = true)
                useCase.updateAutoSyncInterval(interval = "1h")
                useCase.updateSyncOnRefreshEnabled(enabled = true)

                webDavSyncRepository.autoSyncEnabledWrites shouldBe listOf(true)
                webDavSyncRepository.autoSyncIntervalWrites shouldBe listOf("1h")
                webDavSyncRepository.syncOnRefreshEnabledWrites shouldBe listOf(true)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
            }
        }

        test("state observation exposes repository flows") {
            runTest {
                webDavSyncRepository.setEnabled(true)
                webDavSyncRepository.setAutoSyncEnabledValue(true)
                webDavSyncRepository.setAutoSyncIntervalValue("2h")
                webDavSyncRepository.setSyncOnRefreshEnabledValue(true)
                webDavSyncRepository.setLastSyncTimeMillis(5678L)
                webDavSyncRepository.setSyncState(WebDavSyncState.Downloading)
                useCase.updateProvider(WebDavProvider.NEXTCLOUD)
                useCase.updateBaseUrl("https://dav.example.com")
                useCase.updateEndpointUrl("https://dav.example.com/files")
                useCase.updateUsername("alice")

                useCase.observeWebDavSyncEnabled().first() shouldBe true
                useCase.observeProvider().first() shouldBe WebDavProvider.NEXTCLOUD
                useCase.observeBaseUrl().first() shouldBe "https://dav.example.com"
                useCase.observeEndpointUrl().first() shouldBe "https://dav.example.com/files"
                useCase.observeUsername().first() shouldBe "alice"
                useCase.observeAutoSyncEnabled().first() shouldBe true
                useCase.observeAutoSyncInterval().first() shouldBe "2h"
                useCase.observeSyncOnRefreshEnabled().first() shouldBe true
                useCase.observeLastSyncTimeMillis().first() shouldBe 5678L
                useCase.observeSyncState().first() shouldBe WebDavSyncState.Downloading
            }
        }
    }
}
