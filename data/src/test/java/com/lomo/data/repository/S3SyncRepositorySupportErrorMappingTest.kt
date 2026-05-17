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
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Test Contract:
 * - Unit under test: S3SyncRepositorySupport
 * - Behavior focus: connection-test error mapping should promote nested transport diagnostics over generic wrapper failures.
 * - Observable outcomes: returned S3SyncResult.Error code and user-visible message.
 * - Red phase: Fails before the fix because a generic "S3 sync failed" wrapper stays UNKNOWN and hides the nested timeout detail.
 * - Excludes: AWS SDK transport execution, sync planning, credential persistence, and UI rendering.
 */
class S3SyncRepositorySupportErrorMappingTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("mapConnectionTestError prefers nested timeout detail over generic wrapper message") { `mapConnectionTestError prefers nested timeout detail over generic wrapper message`() }

        test("mapConnectionTestError surfaces http status metadata when exception message is empty") { `mapConnectionTestError surfaces http status metadata when exception message is empty`() }

        test("mapConnectionTestError falls back to exception type when no detail fields exist") { `mapConnectionTestError falls back to exception type when no detail fields exist`() }
    }


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

    private fun setUp() {
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
                    performanceTuner = DisabledSyncPerformanceTuner,
                    transactionRunner = NoOpS3SyncTransactionRunner,
                ),
            )
    }

    private fun `mapConnectionTestError prefers nested timeout detail over generic wrapper message`() {
        val result =
            support.mapConnectionTestError(
                IllegalStateException(
                    "S3 sync failed",
                    RuntimeException(
                        "TLS handshake timed out while connecting to https://s3.example.com",
                    ),
                ),
            )

        result.code shouldBe S3SyncErrorCode.CONNECTION_FAILED
        (result.message.contains("timed out", ignoreCase = true)).shouldBeTrue()
        (result.message.contains("https://s3.example.com")).shouldBeTrue()
        (result.message.equals("S3 sync failed", ignoreCase = true)).shouldBeFalse()
    }

    private fun `mapConnectionTestError surfaces http status metadata when exception message is empty`() {
        val exception =
            HttpResponseException().apply {
                statusCode = HttpStatusCode(403, "Forbidden")
            }

        val result = support.mapConnectionTestError(exception)

        result.code shouldBe S3SyncErrorCode.AUTH_FAILED
        (result.message.contains("403")).shouldBeTrue()
        (result.message.contains("Forbidden")).shouldBeTrue()
        (result.message.equals("S3 connection failed", ignoreCase = true)).shouldBeFalse()
    }

    private fun `mapConnectionTestError falls back to exception type when no detail fields exist`() {
        val result = support.mapConnectionTestError(HttpResponseException())

        (result.message.contains("HttpResponseException")).shouldBeTrue()
        (result.message.equals("S3 connection failed", ignoreCase = true)).shouldBeFalse()
    }
}
