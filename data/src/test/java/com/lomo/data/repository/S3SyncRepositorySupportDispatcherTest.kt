package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncRepositorySupport
 * - Behavior focus: shared S3 client execution must switch remote work off the caller thread before invoking transport code.
 * - Observable outcomes: thread used to execute the withClient block.
 * - Red phase: Fails before the fix because withClient executes the client block on the caller thread, allowing NetworkOnMainThreadException from settings-triggered S3 checks.
 * - Excludes: AWS SDK transport behavior, sync planning, error mapping, and UI rendering.
 */
class S3SyncRepositorySupportDispatcherTest {
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
        every { client.close() } returns Unit
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
    fun `withClient runs remote work off the caller thread`() {
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
                encryptionMode = S3EncryptionMode.NONE,
                encryptionPassword = null,
            )
        val callerExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ui-thread") }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()

        try {
            val executingThreadName =
                runBlocking(callerDispatcher) {
                    support.withClient(config) {
                        Thread.currentThread().name
                    }
                }

            assertFalse(executingThreadName.contains("ui-thread"))
        } finally {
            callerDispatcher.close()
            callerExecutor.shutdownNow()
        }
    }
}
