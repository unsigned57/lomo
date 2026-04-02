package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncErrorCode
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.middleware.HttpResponseException
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncRepositorySupport
 * - Behavior focus: connection-test error mapping should promote nested transport diagnostics over generic wrapper failures.
 * - Observable outcomes: returned S3SyncResult.Error code and user-visible message.
 * - Red phase: Fails before the fix because a generic "S3 sync failed" wrapper stays UNKNOWN and hides the nested timeout detail.
 * - Excludes: AWS SDK transport execution, sync planning, credential persistence, and UI rendering.
 */
class S3SyncRepositorySupportErrorMappingTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var clientFactory: LomoS3ClientFactory

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
    fun `mapConnectionTestError prefers nested timeout detail over generic wrapper message`() {
        val result =
            support.mapConnectionTestError(
                IllegalStateException(
                    "S3 sync failed",
                    RuntimeException(
                        "TLS handshake timed out while connecting to https://s3.example.com",
                    ),
                ),
            )

        assertEquals(S3SyncErrorCode.CONNECTION_FAILED, result.code)
        assertTrue(result.message.contains("timed out", ignoreCase = true))
        assertTrue(result.message.contains("https://s3.example.com"))
        assertFalse(result.message.equals("S3 sync failed", ignoreCase = true))
    }

    @Test
    fun `mapConnectionTestError surfaces http status metadata when exception message is empty`() {
        val exception =
            HttpResponseException().apply {
                statusCode = HttpStatusCode(403, "Forbidden")
            }

        val result = support.mapConnectionTestError(exception)

        assertEquals(S3SyncErrorCode.AUTH_FAILED, result.code)
        assertTrue(result.message.contains("403"))
        assertTrue(result.message.contains("Forbidden"))
        assertFalse(result.message.equals("S3 connection failed", ignoreCase = true))
    }

    @Test
    fun `mapConnectionTestError falls back to exception type when no detail fields exist`() {
        val result = support.mapConnectionTestError(HttpResponseException())

        assertTrue(result.message.contains("HttpResponseException"))
        assertFalse(result.message.equals("S3 connection failed", ignoreCase = true))
    }
}
