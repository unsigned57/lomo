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


import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeS3SyncRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: S3SyncSettingsUseCase
 * - Behavior focus: S3 settings orchestration must expose and mutate the full rclone crypt configuration contract, including password2 and filename strategy fields.
 * - Observable outcomes: fake repository state, credential status checks, and observed rclone configuration flows.
 * - TDD proof: Fails before the fix because the use case only exposes the coarse encryption mode plus a single password field.
 * - Excludes: S3 transport behavior, codec internals, and UI rendering.
 */
class S3SyncSettingsUseCaseStrongCompatTest : DomainFunSpec() {
    private lateinit var s3SyncRepository: FakeS3SyncRepository
    private lateinit var useCase: S3SyncSettingsUseCase

    init {
        beforeTest {
            s3SyncRepository = FakeS3SyncRepository()
            useCase =
                S3SyncSettingsUseCase(
                    s3SyncRepository = s3SyncRepository,
                    syncPolicyRepository = FakeSyncPolicyRepository(),
                    syncAndRebuildUseCase =
                        SyncAndRebuildUseCase(
                            memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(FakeMemoStore()),
                            syncProviderRegistry = SyncProviderRegistry(emptySet()),
                            syncPolicyRepository = FakeSyncPolicyRepository(),
                        ),
                )
        }

        test("advanced rclone settings update fake repository state") {
            runTest {
                useCase.updateEncryptionPassword2("secret-salt")
                useCase.updateRcloneFilenameEncryption(S3RcloneFilenameEncryption.OBFUSCATE)
                useCase.updateRcloneFilenameEncoding(S3RcloneFilenameEncoding.BASE32768)
                useCase.updateRcloneDirectoryNameEncryption(false)
                useCase.updateRcloneDataEncryptionEnabled(false)
                useCase.updateRcloneEncryptedSuffix("none")

                s3SyncRepository.encryptionPassword2Writes shouldBe listOf("secret-salt")
                s3SyncRepository.filenameEncryptionWrites shouldBe listOf(S3RcloneFilenameEncryption.OBFUSCATE)
                s3SyncRepository.filenameEncodingWrites shouldBe listOf(S3RcloneFilenameEncoding.BASE32768)
                s3SyncRepository.directoryNameEncryptionWrites shouldBe listOf(false)
                s3SyncRepository.dataEncryptionEnabledWrites shouldBe listOf(false)
                s3SyncRepository.encryptedSuffixWrites shouldBe listOf("none")
            }
        }

        test("advanced credential status includes password2") {
            runTest {
                useCase.updateEncryptionPassword2("secret-salt")

                useCase.isEncryptionPassword2Configured() shouldBe true
            }
        }

        test("advanced rclone settings are observable") {
            runTest {
                useCase.updateRcloneFilenameEncryption(S3RcloneFilenameEncryption.OBFUSCATE)
                useCase.updateRcloneFilenameEncoding(S3RcloneFilenameEncoding.BASE32768)
                useCase.updateRcloneDirectoryNameEncryption(false)
                useCase.updateRcloneDataEncryptionEnabled(false)
                useCase.updateRcloneEncryptedSuffix("none")

                useCase.observeRcloneFilenameEncryption().first() shouldBe S3RcloneFilenameEncryption.OBFUSCATE
                useCase.observeRcloneFilenameEncoding().first() shouldBe S3RcloneFilenameEncoding.BASE32768
                useCase.observeRcloneDirectoryNameEncryption().first() shouldBe false
                useCase.observeRcloneDataEncryptionEnabled().first() shouldBe false
                useCase.observeRcloneEncryptedSuffix().first() shouldBe "none"
            }
        }
    }
}
