package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3RcloneCryptCompatCodec
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.CredentialSecretReadResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: S3SyncStatusTester
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: validate S3 connection compatibility while publishing only safe diagnostic categories and setup guidance.
 *
 * Scenarios:
 * - Given compatible encrypted or plaintext remote objects, when the connection check runs, then it succeeds.
 * - Given encrypted listing samples are incompatible, when the connection check returns an error, then the message explains the encryption/prefix setup category without raw prefix or sample object keys.
 * - Given access verification stalls, when the connection check returns an error, then the message explains the connection category without raw prefix.
 * - Given ignored external objects are the only scanned objects, when the connection check returns an error, then the message explains the remote-root scope category without raw object samples.
 *
 * - Observable outcomes: returned S3SyncResult and client verification/listing calls.
 * - TDD proof: Fails before the fix because encrypted-listing, timeout, and remote-root messages include raw prefix and sample key details.
 * - Excludes: AWS SDK transport behavior, full sync planning, metadata persistence, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class S3SyncStatusTesterTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("testConnection succeeds when a valid rclone object exists") { `testConnection succeeds when a valid rclone object exists`() }

        test("testConnection succeeds when a remotely save base64url rclone object exists") { `testConnection succeeds when a remotely save base64url rclone object exists`() }

        test("testConnection succeeds when a later valid rclone object exists after an undecodable entry") { `testConnection succeeds when a later valid rclone object exists after an undecodable entry`() }

        test("testConnection succeeds when first compatible key appears after sample window") { `testConnection succeeds when first compatible key appears after sample window`() }

        test("testConnection returns actionable encryption detail when sampled keys are incompatible") { `testConnection returns actionable encryption detail when sampled keys are incompatible`() }

        test("testConnection treats undecodable ciphertext-like keys as encryption mismatch instead of external root mismatch") { `testConnection treats undecodable ciphertext-like keys as encryption mismatch instead of external root mismatch`() }

        test("testConnection ignores hidden and unsupported objects when a valid content object exists later") { `testConnection ignores hidden and unsupported objects when a valid content object exists later`() }

        test("testConnection succeeds in plaintext mode when sampled objects include syncable content") { `testConnection succeeds in plaintext mode when sampled objects include syncable content`() }

        test("testConnection returns vault root mismatch in plaintext mode when sampled objects are all ignored") { `testConnection returns vault root mismatch in plaintext mode when sampled objects are all ignored`() }

        test("testConnection returns vault root mismatch when sampled objects contain only ignored external files") { `testConnection returns vault root mismatch when sampled objects contain only ignored external files`() }

        test("testConnection returns timeout error when access verification stalls") { `testConnection returns timeout error when access verification stalls`() }
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

    private lateinit var tester: S3SyncStatusTester

    private fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("prefix")
        every { dataStore.s3PathStyle } returns flowOf("path_style")
        every { dataStore.s3EncryptionMode } returns flowOf("rclone_crypt")
        every { dataStore.s3RcloneFilenameEncryption } returns flowOf("standard")
        every { dataStore.s3RcloneFilenameEncoding } returns flowOf("base64")
        every { dataStore.s3RcloneDirectoryNameEncryption } returns flowOf(true)
        every { dataStore.s3RcloneDataEncryptionEnabled } returns flowOf(true)
        every { dataStore.s3RcloneEncryptedSuffix } returns flowOf(".bin")
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf("/vault/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/vault/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/vault/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getSecret(CredentialField.S3_ACCESS_KEY_ID) } returns "access"
        every { credentialStore.getSecret(CredentialField.S3_SECRET_ACCESS_KEY) } returns "secret"
        every { credentialStore.getSecret(CredentialField.S3_SESSION_TOKEN) } returns null
        every { credentialStore.getSecret(CredentialField.S3_ENCRYPTION_PASSWORD) } returns "secret-pass"
        every { credentialStore.getSecret(CredentialField.S3_ENCRYPTION_PASSWORD2) } returns null
        every { clientFactory.create(any()) } returns client

        val runtime =
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
            )
        tester =
            S3SyncStatusTester(
                runtime = runtime,
                support = S3SyncRepositorySupport(
                    runtime = runtime,
                    credentialRepository =
                        testS3CredentialRepository(
                            encryptionPassword = CredentialSecretReadResult.Present("secret-pass"),
                        ),
                    securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
                ),
                encodingSupport = S3SyncEncodingSupport(),
                fileBridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport()),
                protocolStateStore = DisabledS3SyncProtocolStateStore,
                localChangeJournalStore = DisabledS3LocalChangeJournalStore,
                remoteIndexStore = DisabledS3RemoteIndexStore,
                remoteShardStateStore = DisabledS3RemoteShardStateStore,
            )
    }

    private fun `testConnection succeeds when a valid rclone object exists`() =
        runTest {
            val validRemoteKey =
                "prefix/" + S3RcloneCryptCompatCodec().encryptKey("lomo/memo/note.md", "secret-pass")
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns listOf(validRemoteKey)

            val result = tester.testConnection()

            result shouldBe S3SyncResult.Success("S3 connection successful")
        }

    private fun `testConnection succeeds when a remotely save base64url rclone object exists`() =
        runTest {
            val validRemoteKey =
                "prefix/Y3nf7vUZhT99GJAC1Bqipg/85UUhLN5OijcHlZs6pU07A/9vWZAnc9N8y-EfXu9VYmfQ"
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns listOf(validRemoteKey)

            val result = tester.testConnection()

            result shouldBe S3SyncResult.Success("S3 connection successful")
        }

    private fun `testConnection succeeds when a later valid rclone object exists after an undecodable entry`() =
        runTest {
            val validRemoteKey =
                "prefix/" + S3RcloneCryptCompatCodec().encryptKey("lomo/memo/note.md", "secret-pass")
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/plaintext-file.md",
                    validRemoteKey,
                )

            val result = tester.testConnection()

            result shouldBe S3SyncResult.Success("S3 connection successful")
            coVerify(exactly = 1) { client.verifyAccess("prefix/") }
            coVerify(exactly = 1) { client.listKeys(prefix = "prefix/", maxKeys = 32) }
        }

    private fun `testConnection succeeds when first compatible key appears after sample window`() =
        runTest {
            val validRemoteKey =
                "prefix/" + S3RcloneCryptCompatCodec().encryptKey("Projects/note.md", "secret-pass")
            val sampledIncompatibleKeys = (1..32).map { index -> "prefix/plaintext-$index.md" }
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns sampledIncompatibleKeys
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = null) } returns
                sampledIncompatibleKeys + validRemoteKey

            val result = tester.testConnection()

            result shouldBe S3SyncResult.Success("S3 connection successful")
            coVerify(exactly = 1) { client.verifyAccess("prefix/") }
            coVerify(exactly = 1) { client.listKeys(prefix = "prefix/", maxKeys = 32) }
            coVerify(exactly = 1) { client.listKeys(prefix = "prefix/", maxKeys = null) }
        }

    private fun `testConnection returns actionable encryption detail when sampled keys are incompatible`() =
        runTest {
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/plaintext-file.md",
                    "prefix/notes/readme.md",
                )

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.ENCRYPTION_FAILED
            (error.message.contains("RCLONE_CRYPT-compatible object names")).shouldBeTrue()
            (error.message.contains("Check the S3 prefix, encryption mode, and encryption password")).shouldBeTrue()
            assertNoRawS3ConnectionDiagnosticLeak(error.message)
        }

    private fun `testConnection treats undecodable ciphertext-like keys as encryption mismatch instead of external root mismatch`() =
        runTest {
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/ZmFrZV9lbmNyeXB0ZWRfcm9vdA/UGF5bG9hZF9maWxlXzAxX2Jsb2I",
                    "prefix/ZmFrZV9lbmNyeXB0ZWRfcm9vdA/QW5vdGhlcl9wYXlsb2FkXzAyX3NlZw",
                    "prefix/ZmFrZV9lbmNyeXB0ZWRfcm9vdA/U2FtcGxlX2NpcGhlcnRleHRfMDNfY2h1bms",
                )

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.ENCRYPTION_FAILED
            (error.message.contains("RCLONE_CRYPT-compatible object names")).shouldBeTrue()
            (error.message.contains("Check the S3 prefix, encryption mode, and encryption password")).shouldBeTrue()
            assertNoRawS3ConnectionDiagnosticLeak(error.message)
            (error.message.contains("remote root", ignoreCase = true)).shouldBeFalse()
        }

    private fun `testConnection ignores hidden and unsupported objects when a valid content object exists later`() =
        runTest {
            val validRemoteKey =
                "prefix/" + S3RcloneCryptCompatCodec().encryptKey("Projects/note.md", "secret-pass")
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/.obsidian/config",
                    "prefix/.trash/ignored.md",
                    "prefix/plaintext.bin",
                    validRemoteKey,
                )

            val result = tester.testConnection()

            result shouldBe S3SyncResult.Success("S3 connection successful")
        }

    private fun `testConnection succeeds in plaintext mode when sampled objects include syncable content`() =
        runTest {
            every { dataStore.s3EncryptionMode } returns flowOf("none")
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/.obsidian/workspace.json",
                    "prefix/plugins/plugin.json",
                    "prefix/Projects/note.md",
                )

            val result = tester.testConnection()

            result shouldBe S3SyncResult.Success("S3 connection successful")
        }

    private fun `testConnection returns vault root mismatch in plaintext mode when sampled objects are all ignored`() =
        runTest {
            every { dataStore.s3EncryptionMode } returns flowOf("none")
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/.obsidian/workspace.json",
                    "prefix/.hidden/file.md",
                    "prefix/plugins/plugin-data.json",
                )

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.ENCRYPTION_FAILED
            (error.message.contains("remote root")).shouldBeTrue()
            (error.message.contains("content")).shouldBeTrue()
            assertNoRawS3ConnectionDiagnosticLeak(error.message)
        }

    private fun `testConnection returns vault root mismatch when sampled objects contain only ignored external files`() =
        runTest {
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/.obsidian/workspace.json",
                    "prefix/.hidden/file.md",
                    "prefix/plugins/plugin-data.json",
                )

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.ENCRYPTION_FAILED
            (error.message.contains("remote root")).shouldBeTrue()
            (error.message.contains("content")).shouldBeTrue()
            assertNoRawS3ConnectionDiagnosticLeak(error.message)
        }

    private fun `testConnection returns timeout error when access verification stalls`() =
        runTest {
            coEvery { client.verifyAccess("prefix/") } coAnswers {
                delay(20_000)
                Unit
            }

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.CONNECTION_FAILED
            (error.message.contains("timed out", ignoreCase = true)).shouldBeTrue()
            assertNoRawS3ConnectionDiagnosticLeak(error.message)
            coVerify(exactly = 0) { client.listKeys(any(), any()) }
        }

    private fun assertNoRawS3ConnectionDiagnosticLeak(message: String) {
        (message.contains("prefix/")).shouldBeFalse()
        (message.contains("prefix/plaintext-file.md")).shouldBeFalse()
        (message.contains("prefix/notes/readme.md")).shouldBeFalse()
        (message.contains("ZmFrZV9lbmNyeXB0ZWRfcm9vdA")).shouldBeFalse()
        (message.contains(".obsidian")).shouldBeFalse()
        (message.contains(".hidden/file.md")).shouldBeFalse()
        (message.contains("plugins/plugin-data.json")).shouldBeFalse()
    }
}
