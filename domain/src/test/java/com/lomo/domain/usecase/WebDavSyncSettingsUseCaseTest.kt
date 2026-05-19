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


import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import com.lomo.domain.testing.fakes.FakeWebDavSyncRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, auto-sync policy application, and WebDAV action behavior.
 * - Observable outcomes: backend policy writes, fake repository state, memo refresh count, and connection-test result.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: WebDAV transport behavior, repository implementation internals, and UI rendering.
 */
class WebDavSyncSettingsUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private lateinit var webDavSyncRepository: FakeWebDavSyncRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var memoRepository: FakeMemoRepository
    private lateinit var useCase: WebDavSyncSettingsUseCase

    init {
        beforeTest {
            eventLog.clear()
            webDavSyncRepository = FakeWebDavSyncRepository()
            syncPolicyRepository = FakeSyncPolicyRepository(eventLog = eventLog)
            memoRepository = FakeMemoRepository()
            useCase =
                WebDavSyncSettingsUseCase(
                    webDavSyncRepository = webDavSyncRepository,
                    syncPolicyRepository = syncPolicyRepository,
                    syncAndRebuildUseCase =
                        SyncAndRebuildUseCase(
                            memoRepository = memoRepository,
                            syncProviderRegistry = SyncProviderRegistry(emptyList()),
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

        test("triggerSyncNow forces a memo refresh through shared actions") {
            runTest {
                useCase.triggerSyncNow()

                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("testConnection delegates to webdav repository") {
            runTest {
                val expected = WebDavSyncResult.Success("connected")
                webDavSyncRepository.nextTestConnectionResult = expected

                val result = useCase.testConnection()

                result shouldBe expected
                webDavSyncRepository.testConnectionCallCount shouldBe 1
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
