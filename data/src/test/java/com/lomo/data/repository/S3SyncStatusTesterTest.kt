package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3RcloneCryptCompatCodec
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncStatusTester
 * - Behavior focus: S3 connection checks should validate access without failing on unrelated remote keys, accept Remotely Save rclone base64url object names, ignore unsupported external objects, and fail with actionable vault-root mismatch details only when scanned objects contain no syncable content.
 * - Observable outcomes: returned S3SyncResult and client verification/listing calls.
 * - Red phase: Fails before the fix because testConnection rejects Remotely Save base64url rclone object names as invalid base32hex and treats them as incompatible encrypted objects.
 * - Excludes: AWS SDK transport behavior, full sync planning, metadata persistence, and UI rendering.
 */
class S3SyncStatusTesterTest {
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

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("prefix")
        every { dataStore.s3PathStyle } returns flowOf("path_style")
        every { dataStore.s3EncryptionMode } returns flowOf("rclone_crypt")
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf("/vault/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/vault/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/vault/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns "secret-pass"
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
            )
        tester =
            S3SyncStatusTester(
                runtime = runtime,
                support = S3SyncRepositorySupport(runtime),
                encodingSupport = S3SyncEncodingSupport(),
                fileBridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport()),
            )
    }

    @Test
    fun `testConnection succeeds when a valid rclone object exists`() =
        runTest {
            val validRemoteKey =
                "prefix/" + S3RcloneCryptCompatCodec().encryptKey("lomo/memo/note.md", "secret-pass")
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns listOf(validRemoteKey)

            val result = tester.testConnection()

            assertEquals(S3SyncResult.Success("S3 connection successful"), result)
        }

    @Test
    fun `testConnection succeeds when a remotely save base64url rclone object exists`() =
        runTest {
            val validRemoteKey =
                "prefix/Y3nf7vUZhT99GJAC1Bqipg/85UUhLN5OijcHlZs6pU07A/9vWZAnc9N8y-EfXu9VYmfQ"
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns listOf(validRemoteKey)

            val result = tester.testConnection()

            assertEquals(S3SyncResult.Success("S3 connection successful"), result)
        }

    @Test
    fun `testConnection succeeds when a later valid rclone object exists after an undecodable entry`() =
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

            assertEquals(S3SyncResult.Success("S3 connection successful"), result)
            coVerify(exactly = 1) { client.verifyAccess("prefix/") }
            coVerify(exactly = 1) { client.listKeys(prefix = "prefix/", maxKeys = 32) }
        }

    @Test
    fun `testConnection succeeds when first compatible key appears after sample window`() =
        runTest {
            val validRemoteKey =
                "prefix/" + S3RcloneCryptCompatCodec().encryptKey("Projects/note.md", "secret-pass")
            val sampledIncompatibleKeys = (1..32).map { index -> "prefix/plaintext-$index.md" }
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns sampledIncompatibleKeys
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = null) } returns
                sampledIncompatibleKeys + validRemoteKey

            val result = tester.testConnection()

            assertEquals(S3SyncResult.Success("S3 connection successful"), result)
            coVerify(exactly = 1) { client.verifyAccess("prefix/") }
            coVerify(exactly = 1) { client.listKeys(prefix = "prefix/", maxKeys = 32) }
            coVerify(exactly = 1) { client.listKeys(prefix = "prefix/", maxKeys = null) }
        }

    @Test
    fun `testConnection returns actionable encryption detail when sampled keys are incompatible`() =
        runTest {
            coEvery { client.verifyAccess("prefix/") } returns Unit
            coEvery { client.listKeys(prefix = "prefix/", maxKeys = 32) } returns
                listOf(
                    "prefix/plaintext-file.md",
                    "prefix/notes/readme.md",
                )

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            assertEquals(S3SyncErrorCode.ENCRYPTION_FAILED, error.code)
            assertTrue(error.message.contains("No RCLONE_CRYPT-compatible object names were found under prefix 'prefix/'"))
            assertTrue(error.message.contains("prefix/plaintext-file.md"))
            assertTrue(error.message.contains("Check the S3 prefix, encryption mode, and encryption password"))
        }

    @Test
    fun `testConnection treats undecodable ciphertext-like keys as encryption mismatch instead of external root mismatch`() =
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
            assertEquals(S3SyncErrorCode.ENCRYPTION_FAILED, error.code)
            assertTrue(error.message.contains("No RCLONE_CRYPT-compatible object names were found under prefix 'prefix/'"))
            assertTrue(error.message.contains("ZmFrZV9lbmNyeXB0ZWRfcm9vdA"))
            assertTrue(error.message.contains("Check the S3 prefix, encryption mode, and encryption password"))
            assertFalse(error.message.contains("remote root", ignoreCase = true))
        }

    @Test
    fun `testConnection ignores hidden and unsupported objects when a valid content object exists later`() =
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

            assertEquals(S3SyncResult.Success("S3 connection successful"), result)
        }

    @Test
    fun `testConnection succeeds in plaintext mode when sampled objects include syncable content`() =
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

            assertEquals(S3SyncResult.Success("S3 connection successful"), result)
        }

    @Test
    fun `testConnection returns vault root mismatch in plaintext mode when sampled objects are all ignored`() =
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
            assertEquals(S3SyncErrorCode.ENCRYPTION_FAILED, error.code)
            assertTrue(error.message.contains("remote root"))
            assertTrue(error.message.contains("content"))
            assertTrue(error.message.contains(".obsidian"))
        }

    @Test
    fun `testConnection returns vault root mismatch when sampled objects contain only ignored external files`() =
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
            assertEquals(S3SyncErrorCode.ENCRYPTION_FAILED, error.code)
            assertTrue(error.message.contains("remote root"))
            assertTrue(error.message.contains("content"))
            assertTrue(error.message.contains(".obsidian"))
        }

    @Test
    fun `testConnection returns timeout error when access verification stalls`() =
        runTest {
            coEvery { client.verifyAccess("prefix/") } coAnswers {
                delay(20_000)
                Unit
            }

            val result = tester.testConnection()

            val error = result as S3SyncResult.Error
            assertEquals(S3SyncErrorCode.CONNECTION_FAILED, error.code)
            assertTrue(error.message.contains("timed out", ignoreCase = true))
            coVerify(exactly = 0) { client.listKeys(any(), any()) }
        }
}
