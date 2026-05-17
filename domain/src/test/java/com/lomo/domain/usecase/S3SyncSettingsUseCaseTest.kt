package com.lomo.domain.usecase

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
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
 * - Unit under test: S3SyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, S3 settings mutation wiring, and sync action delegation behavior.
 * - Observable outcomes: backend policy writes, repository mutation invocations, and sync action delegation parameters.
 * - Red phase: Fails before the fix because S3 settings orchestration does not exist yet.
 * - Excludes: S3 transport behavior, encryption codec internals, and UI rendering.
 */
class S3SyncSettingsUseCaseTest : DomainFunSpec() {
    private val s3SyncRepository: S3SyncRepository = mockk(relaxed = true)
    private val syncPolicyRepository: SyncPolicyRepository = mockk(relaxed = true)
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk(relaxed = true)

    private val useCase =
        S3SyncSettingsUseCase(
            s3SyncRepository = s3SyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )
    init {
        test("updateS3SyncEnabled true applies S3 backend policy") {
            runTest {
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.S3) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateS3SyncEnabled(enabled = true)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.S3)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateS3SyncEnabled false applies NONE backend policy") {
            runTest {
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateS3SyncEnabled(enabled = false)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("s3 setting mutations delegate to repository") {
            runTest {
                        coEvery { s3SyncRepository.setEndpointUrl("https://s3.example.com") } returns Unit
                        coEvery { s3SyncRepository.setRegion("ap-southeast-1") } returns Unit
                        coEvery { s3SyncRepository.setBucket("vault") } returns Unit
                        coEvery { s3SyncRepository.setPrefix("obsidian") } returns Unit
                        coEvery { s3SyncRepository.setLocalSyncDirectory("content://tree/primary%3AObsidian") } returns Unit
                        coEvery { s3SyncRepository.setAccessKeyId("ak") } returns Unit
                        coEvery { s3SyncRepository.setSecretAccessKey("sk") } returns Unit
                        coEvery { s3SyncRepository.setSessionToken("token") } returns Unit
                        coEvery { s3SyncRepository.setPathStyle(S3PathStyle.PATH_STYLE) } returns Unit
                        coEvery { s3SyncRepository.setEncryptionMode(S3EncryptionMode.RCLONE_CRYPT) } returns Unit
                        coEvery { s3SyncRepository.setEncryptionPassword("secret") } returns Unit

                        useCase.updateEndpointUrl("https://s3.example.com")
                        useCase.updateRegion("ap-southeast-1")
                        useCase.updateBucket("vault")
                        useCase.updatePrefix("obsidian")
                        useCase.updateLocalSyncDirectory("content://tree/primary%3AObsidian")
                        useCase.updateAccessKeyId("ak")
                        useCase.updateSecretAccessKey("sk")
                        useCase.updateSessionToken("token")
                        useCase.updatePathStyle(S3PathStyle.PATH_STYLE)
                        useCase.updateEncryptionMode(S3EncryptionMode.RCLONE_CRYPT)
                        useCase.updateEncryptionPassword("secret")

                        coVerify(exactly = 1) { s3SyncRepository.setEndpointUrl("https://s3.example.com") }
                        coVerify(exactly = 1) { s3SyncRepository.setRegion("ap-southeast-1") }
                        coVerify(exactly = 1) { s3SyncRepository.setBucket("vault") }
                        coVerify(exactly = 1) { s3SyncRepository.setPrefix("obsidian") }
                        coVerify(exactly = 1) { s3SyncRepository.setLocalSyncDirectory("content://tree/primary%3AObsidian") }
                        coVerify(exactly = 1) { s3SyncRepository.setAccessKeyId("ak") }
                        coVerify(exactly = 1) { s3SyncRepository.setSecretAccessKey("sk") }
                        coVerify(exactly = 1) { s3SyncRepository.setSessionToken("token") }
                        coVerify(exactly = 1) { s3SyncRepository.setPathStyle(S3PathStyle.PATH_STYLE) }
                        coVerify(exactly = 1) { s3SyncRepository.setEncryptionMode(S3EncryptionMode.RCLONE_CRYPT) }
                        coVerify(exactly = 1) { s3SyncRepository.setEncryptionPassword("secret") }
                    }
        }
    }
    init {
        test("clearLocalSyncDirectory delegates to repository") {
            runTest {
                        coEvery { s3SyncRepository.clearLocalSyncDirectory() } returns Unit

                        useCase.clearLocalSyncDirectory()

                        coVerify(exactly = 1) { s3SyncRepository.clearLocalSyncDirectory() }
                    }
        }
    }
    init {
        test("updateAutoSyncEnabled writes flag and reapplies policy") {
            runTest {
                        coEvery { s3SyncRepository.setAutoSyncEnabled(true) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateAutoSyncEnabled(enabled = true)

                        coVerifyOrder {
                            s3SyncRepository.setAutoSyncEnabled(true)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateAutoSyncInterval writes interval and reapplies policy") {
            runTest {
                        coEvery { s3SyncRepository.setAutoSyncInterval("1h") } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateAutoSyncInterval(interval = "1h")

                        coVerifyOrder {
                            s3SyncRepository.setAutoSyncInterval("1h")
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateSyncOnRefreshEnabled only writes repository flag") {
            runTest {
                        coEvery { s3SyncRepository.setSyncOnRefreshEnabled(true) } returns Unit

                        useCase.updateSyncOnRefreshEnabled(enabled = true)

                        coVerify(exactly = 1) { s3SyncRepository.setSyncOnRefreshEnabled(true) }
                        coVerify(exactly = 0) { syncPolicyRepository.applyRemoteSyncPolicy() }
                    }
        }
    }
    init {
        test("credential status delegates to repository") {
            runTest {
                        coEvery { s3SyncRepository.isAccessKeyConfigured() } returns true
                        coEvery { s3SyncRepository.isSecretAccessKeyConfigured() } returns true
                        coEvery { s3SyncRepository.isEncryptionPasswordConfigured() } returns true

                        (useCase.isAccessKeyConfigured()) shouldBe true
                        (useCase.isSecretAccessKeyConfigured()) shouldBe true
                        (useCase.isEncryptionPasswordConfigured()) shouldBe true
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
        test("testConnection delegates to s3 repository") {
            runTest {
                        val expected = S3SyncResult.Success("connected")
                        coEvery { s3SyncRepository.testConnection() } returns expected

                        val result = useCase.testConnection()

                        result shouldBe expected
                        coVerify(exactly = 1) { s3SyncRepository.testConnection() }
                    }
        }
    }
    init {
        test("state observation delegates expose repository flows") {
            runTest {
                        every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(true)
                        every { s3SyncRepository.getEndpointUrl() } returns flowOf("https://s3.example.com")
                        every { s3SyncRepository.getRegion() } returns flowOf("ap-southeast-1")
                        every { s3SyncRepository.getBucket() } returns flowOf("vault")
                        every { s3SyncRepository.getPrefix() } returns flowOf("obsidian")
                        every { s3SyncRepository.getLocalSyncDirectory() } returns flowOf("content://tree/primary%3AObsidian")
                        every { s3SyncRepository.getPathStyle() } returns flowOf(S3PathStyle.PATH_STYLE)
                        every { s3SyncRepository.getEncryptionMode() } returns flowOf(S3EncryptionMode.RCLONE_CRYPT)
                        every { s3SyncRepository.getAutoSyncEnabled() } returns flowOf(true)
                        every { s3SyncRepository.getAutoSyncInterval() } returns flowOf("2h")
                        every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { s3SyncRepository.observeLastSyncTimeMillis() } returns flowOf(5678L)
                        every { s3SyncRepository.syncState() } returns flowOf(S3SyncState.Uploading)

                        useCase.observeS3SyncEnabled().first() shouldBe true
                        useCase.observeEndpointUrl().first() shouldBe "https://s3.example.com"
                        useCase.observeRegion().first() shouldBe "ap-southeast-1"
                        useCase.observeBucket().first() shouldBe "vault"
                        useCase.observePrefix().first() shouldBe "obsidian"
                        useCase.observeLocalSyncDirectory().first() shouldBe "content://tree/primary%3AObsidian"
                        useCase.observePathStyle().first() shouldBe S3PathStyle.PATH_STYLE
                        useCase.observeEncryptionMode().first() shouldBe S3EncryptionMode.RCLONE_CRYPT
                        useCase.observeAutoSyncEnabled().first() shouldBe true
                        useCase.observeAutoSyncInterval().first() shouldBe "2h"
                        useCase.observeSyncOnRefreshEnabled().first() shouldBe true
                        useCase.observeLastSyncTimeMillis().first() shouldBe 5678L
                        useCase.observeSyncState().first() shouldBe S3SyncState.Uploading
                    }
        }
    }
}
