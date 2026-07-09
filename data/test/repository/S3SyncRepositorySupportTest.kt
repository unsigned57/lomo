package com.lomo.data.repository

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



import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncRepositorySupport
 * - Behavior focus: runtime config validation for encrypted S3 clients.
 * - Observable outcomes: withClient invokes the provided block for supported rclone crypt configs and still closes the client.
 * - TDD proof: Fails before the fix because validateEncryptionSupport rejects every RCLONE_CRYPT config as not implemented.
 * - Excludes: AWS SDK transport behavior, sync planning, file bridge behavior, and UI rendering.
 */
class S3SyncRepositorySupportTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("withClient accepts rclone crypt config when password is present") { `withClient accepts rclone crypt config when password is present`() }

        test("withClient rejects insecure http endpoint by default") { `withClient rejects insecure http endpoint by default`() }

        test("given session is locked when S3 config is resolved then authorization failure is surfaced") {
            `given session is locked when S3 config is resolved then authorization failure is surfaced`()
        }

        test("given access key is unreadable when S3 config is resolved then unreadable credential is surfaced") {
            `given access key is unreadable when S3 config is resolved then unreadable credential is surfaced`()
        }
    }


    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var clientFactory: LomoS3ClientFactory

    @MockK(relaxed = true)
    private lateinit var client: LomoS3Client

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var metadataDao: S3SyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var support: S3SyncRepositorySupport

    private fun setUp() {
        MockKAnnotations.init(this)
        every { clientFactory.create(any()) } returns client
        support = createSupport()
    }

    private fun `withClient accepts rclone crypt config when password is present`() =
        runTest {
            val config =
                S3ResolvedConfig(
                    endpointUrl = "https://s3.example.com",
                    region = "us-east-1",
                    bucket = "bucket",
                    prefix = "vault",
                    accessKeyId = "access",
                    secretAccessKey = "secret",
                    sessionToken = null,
                    pathStyle = S3PathStyle.AUTO,
                    encryptionMode = S3EncryptionMode.RCLONE_CRYPT,
                    encryptionPassword = "secret-pass",
                )
            every { client.close() } returns Unit

            val result = support.withClient(config) { "ok" }

            result shouldBe "ok"
            verify(exactly = 1) { clientFactory.create(config) }
            verify(exactly = 1) { client.close() }
        }

    private fun `withClient rejects insecure http endpoint by default`() =
        runTest {
            val config =
                S3ResolvedConfig(
                    endpointUrl = "http://s3.example.com",
                    region = "us-east-1",
                    bucket = "bucket",
                    prefix = "vault",
                    accessKeyId = "access",
                    secretAccessKey = "secret",
                    sessionToken = null,
                    pathStyle = S3PathStyle.AUTO,
                    encryptionMode = S3EncryptionMode.NONE,
                    encryptionPassword = null,
                )

            val failure =
                runCatching { support.withClient(config) { "unexpected" } }.exceptionOrNull()
                    as? S3SyncFailureException

            requireNotNull(failure)
            failure.code shouldBe S3SyncErrorCode.CONNECTION_FAILED
            failure.message shouldBe "S3 endpoint must use HTTPS unless insecure HTTP is explicitly allowed"
            verify(exactly = 0) { clientFactory.create(any()) }
        }

    private fun `given session is locked when S3 config is resolved then authorization failure is surfaced`() =
        runTest {
            stubEnabledConfig()
            val lockedSupport = createSupport(securitySessionPolicy = LockedCredentialReadSessionPolicy)

            val failure = shouldThrow<S3SyncFailureException> {
                lockedSupport.resolveConfig()
            }

            failure.code shouldBe S3SyncErrorCode.AUTH_FAILED
            failure.message shouldBe
                "S3 credential read denied: ${CredentialReadDenialReason.SecuritySessionLocked}"
        }

    private fun `given access key is unreadable when S3 config is resolved then unreadable credential is surfaced`() =
        runTest {
            stubEnabledConfig()
            val unreadableSupport =
                createSupport(
                    credentialRepository =
                        testS3CredentialRepository(
                            accessKeyId = CredentialSecretReadResult.Unreadable,
                        ),
                )

            val failure = shouldThrow<S3SyncFailureException> {
                unreadableSupport.resolveConfig()
            }

            failure.code shouldBe S3SyncErrorCode.AUTH_FAILED
            failure.message shouldBe "S3 credential S3_ACCESS_KEY_ID is unreadable"
        }

    private fun stubEnabledConfig() {
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("")
        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3PathStyle } returns flowOf(S3PathStyle.AUTO.preferenceValue)
        every { dataStore.s3EncryptionMode } returns flowOf(S3EncryptionMode.NONE.preferenceValue)
        every { dataStore.s3RcloneFilenameEncryption } returns flowOf("standard")
        every { dataStore.s3RcloneDirectoryNameEncryption } returns flowOf(true)
        every { dataStore.s3RcloneFilenameEncoding } returns flowOf("base64")
        every { dataStore.s3RcloneDataEncryptionEnabled } returns flowOf(true)
        every { dataStore.s3RcloneEncryptedSuffix } returns flowOf(".bin")
    }

    private fun createSupport(
        credentialRepository: CredentialRepository = testS3CredentialRepository(),
        securitySessionPolicy: SecuritySessionPolicy = AuthorizedCredentialReadSessionPolicy,
    ): S3SyncRepositorySupport =
        S3SyncRepositorySupport(
            runtime =
                S3SyncRepositoryContext(
                    dataStore = dataStore,
                    credentialStore = credentialStore,
                    clientFactory = clientFactory,
                    markdownStorageDataSource = markdownStorageDataSource,
                    localMediaSyncStore = localMediaSyncStore,
                    metadataDao = metadataDao,
                    memoSynchronizer = memoSynchronizer,
                    planner = S3SyncPlanner(),
                    stateHolder = S3SyncStateHolder(),
                    performanceTuner = DisabledSyncPerformanceTuner,
                    transactionRunner = NoOpS3SyncTransactionRunner,
                ),
            credentialRepository = credentialRepository,
            securitySessionPolicy = securitySessionPolicy,
        )
}
