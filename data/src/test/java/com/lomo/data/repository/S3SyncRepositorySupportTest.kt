package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3PathStyle
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncRepositorySupport
 * - Behavior focus: runtime config validation for encrypted S3 clients.
 * - Observable outcomes: withClient invokes the provided block for supported rclone crypt configs and still closes the client.
 * - Red phase: Fails before the fix because validateEncryptionSupport rejects every RCLONE_CRYPT config as not implemented.
 * - Excludes: AWS SDK transport behavior, sync planning, file bridge behavior, and UI rendering.
 */
class S3SyncRepositorySupportTest {
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

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { clientFactory.create(any()) } returns client
        support =
            S3SyncRepositorySupport(
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
                ),
            )
    }

    @Test
    fun `withClient accepts rclone crypt config when password is present`() =
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

            assertEquals("ok", result)
            verify(exactly = 1) { clientFactory.create(config) }
            verify(exactly = 1) { client.close() }
        }

    @Test
    fun `withClient rejects insecure http endpoint by default`() =
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
            assertEquals(S3SyncErrorCode.CONNECTION_FAILED, failure.code)
            assertEquals(
                "S3 endpoint must use HTTPS unless insecure HTTP is explicitly allowed",
                failure.message,
            )
            verify(exactly = 0) { clientFactory.create(any()) }
        }
}
