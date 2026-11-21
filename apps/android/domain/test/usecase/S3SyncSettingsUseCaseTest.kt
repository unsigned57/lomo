package com.lomo.domain.usecase

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoMutationRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeS3SyncRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class S3SyncSettingsUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private lateinit var s3SyncRepository: FakeS3SyncRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var useCase: S3SyncSettingsUseCase

    init {
        beforeTest {
            eventLog.clear()
            s3SyncRepository = FakeS3SyncRepository()
            syncPolicyRepository = FakeSyncPolicyRepository(eventLog = eventLog)
            useCase =
                S3SyncSettingsUseCase(
                    s3SyncRepository = s3SyncRepository,
                    syncPolicyRepository = syncPolicyRepository,
                    syncAndRebuildUseCase =
                    SyncAndRebuildUseCase(
                        memoRepository = FakeMemoMutationRepository(FakeMemoStore()),
                        syncProviderRegistry = SyncProviderRegistry(emptySet()),
                        syncPolicyRepository = syncPolicyRepository,
                    ),
                )
        }

        test("updateS3SyncEnabled applies backend policy") {
            runTest {
                useCase.updateS3SyncEnabled(enabled = true)
                useCase.updateS3SyncEnabled(enabled = false)

                syncPolicyRepository.setBackendRequests shouldBe
                    listOf(SyncBackendType.S3, SyncBackendType.NONE)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
                eventLog shouldBe
                    listOf(
                        "syncPolicy.setRemoteSyncBackend:S3",
                        "syncPolicy.applyRemoteSyncPolicy",
                        "syncPolicy.setRemoteSyncBackend:NONE",
                        "syncPolicy.applyRemoteSyncPolicy",
                    )
            }
        }

        test("s3 setting mutations update fake repository state") {
            runTest {
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

                s3SyncRepository.endpointUrlWrites shouldBe listOf("https://s3.example.com")
                s3SyncRepository.regionWrites shouldBe listOf("ap-southeast-1")
                s3SyncRepository.bucketWrites shouldBe listOf("vault")
                s3SyncRepository.prefixWrites shouldBe listOf("obsidian")
                s3SyncRepository.localSyncDirectoryWrites shouldBe listOf("content://tree/primary%3AObsidian")
                s3SyncRepository.accessKeyWrites shouldBe listOf("ak")
                s3SyncRepository.secretAccessKeyWrites shouldBe listOf("sk")
                s3SyncRepository.sessionTokenWrites shouldBe listOf("token")
                s3SyncRepository.pathStyleWrites shouldBe listOf(S3PathStyle.PATH_STYLE)
                s3SyncRepository.encryptionModeWrites shouldBe listOf(S3EncryptionMode.RCLONE_CRYPT)
                s3SyncRepository.encryptionPasswordWrites shouldBe listOf("secret")
            }
        }

        test("clearLocalSyncDirectory clears fake repository state") {
            runTest {
                useCase.updateLocalSyncDirectory("content://tree/primary%3AObsidian")

                useCase.clearLocalSyncDirectory()

                s3SyncRepository.clearLocalSyncDirectoryCallCount shouldBe 1
                useCase.observeLocalSyncDirectory().first() shouldBe null
            }
        }

        test("auto sync mutations reapply policy while sync-on-refresh only writes flag") {
            runTest {
                useCase.updateAutoSyncEnabled(enabled = true)
                useCase.updateAutoSyncInterval(interval = "1h")
                useCase.updateSyncOnRefreshEnabled(enabled = true)

                s3SyncRepository.autoSyncEnabledWrites shouldBe listOf(true)
                s3SyncRepository.autoSyncIntervalWrites shouldBe listOf("1h")
                s3SyncRepository.syncOnRefreshEnabledWrites shouldBe listOf(true)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
            }
        }

        test("credential status reflects repository configuration") {
            runTest {
                useCase.updateAccessKeyId("ak")
                useCase.updateSecretAccessKey("sk")
                useCase.updateEncryptionPassword("secret")

                useCase.isAccessKeyConfigured() shouldBe true
                useCase.isSecretAccessKeyConfigured() shouldBe true
                useCase.isEncryptionPasswordConfigured() shouldBe true
            }
        }

        test("state observation exposes repository flows") {
            runTest {
                s3SyncRepository.setEnabled(true)
                s3SyncRepository.setAutoSyncEnabledValue(true)
                s3SyncRepository.setAutoSyncIntervalValue("2h")
                s3SyncRepository.setSyncOnRefreshEnabledValue(true)
                s3SyncRepository.setLastSyncTimeMillis(5678L)
                s3SyncRepository.setSyncState(S3SyncState.Uploading)
                useCase.updateEndpointUrl("https://s3.example.com")
                useCase.updateRegion("ap-southeast-1")
                useCase.updateBucket("vault")
                useCase.updatePrefix("obsidian")
                useCase.updateLocalSyncDirectory("content://tree/primary%3AObsidian")
                useCase.updatePathStyle(S3PathStyle.PATH_STYLE)
                useCase.updateEncryptionMode(S3EncryptionMode.RCLONE_CRYPT)

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
