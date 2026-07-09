package com.lomo.data.repository

import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.middleware.HttpResponseException
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncErrorCode
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncRepositorySupport
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: classify raw S3 transport/provider diagnostics without exposing them as public sync messages.
 *
 * Scenarios:
 * - Given a nested timeout includes a request URL, when connection-test error mapping runs, then the result is CONNECTION_FAILED with safe actionable guidance and no raw URL.
 * - Given provider text includes status, bucket, prefix, key, URL, and provider name, when connection-test error mapping runs, then the result keeps the classified failure code and redacts raw details.
 * - Given no diagnostic text exists, when connection-test error mapping runs, then the result uses a safe generic S3 guidance message instead of exception type text.
 *
 * - Observable outcomes: returned S3SyncResult.Error code and user-visible message.
 * - TDD proof: Fails before the fix because raw nested URL/provider diagnostics are selected as the public result message.
 * - Excludes: AWS SDK transport execution, sync planning, credential persistence, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class S3SyncRepositorySupportErrorMappingTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("mapConnectionTestError classifies nested timeout without exposing request URL") { `mapConnectionTestError classifies nested timeout without exposing request URL`() }

        test("mapConnectionTestError redacts provider status bucket prefix key and URL diagnostics") { `mapConnectionTestError redacts provider status bucket prefix key and URL diagnostics`() }

        test("mapConnectionTestError uses safe generic guidance when no detail fields exist") { `mapConnectionTestError uses safe generic guidance when no detail fields exist`() }
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
                runtime = S3SyncRepositoryContext(
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
                credentialRepository = testS3CredentialRepository(),
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
            )
    }

    private fun `mapConnectionTestError classifies nested timeout without exposing request URL`() {
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
        (result.message.contains("connection", ignoreCase = true)).shouldBeTrue()
        (result.message.contains("endpoint", ignoreCase = true)).shouldBeTrue()
        assertNoRawS3DiagnosticLeak(result.message)
        (result.message.equals("S3 sync failed", ignoreCase = true)).shouldBeFalse()
    }

    private fun `mapConnectionTestError redacts provider status bucket prefix key and URL diagnostics`() {
        val exception =
            IllegalStateException(
                "MinIO 403 Forbidden for bucket 'private-bucket' key 'prefix/private-note.md' " +
                    "at https://minio.example.com/private-bucket/prefix/private-note.md",
                HttpResponseException().apply {
                    statusCode = HttpStatusCode(403, "Forbidden")
                },
            )

        val result = support.mapConnectionTestError(exception)

        result.code shouldBe S3SyncErrorCode.AUTH_FAILED
        (result.message.contains("credential", ignoreCase = true)).shouldBeTrue()
        assertNoRawS3DiagnosticLeak(result.message)
        (result.message.equals("S3 connection failed", ignoreCase = true)).shouldBeFalse()
    }

    private fun `mapConnectionTestError uses safe generic guidance when no detail fields exist`() {
        val result = support.mapConnectionTestError(HttpResponseException())

        result.code shouldBe S3SyncErrorCode.UNKNOWN
        (result.message.contains("S3", ignoreCase = true)).shouldBeTrue()
        assertNoRawS3DiagnosticLeak(result.message)
        (result.message.equals("S3 connection failed", ignoreCase = true)).shouldBeFalse()
    }

    private fun assertNoRawS3DiagnosticLeak(message: String) {
        (message.contains("https://s3.example.com")).shouldBeFalse()
        (message.contains("https://minio.example.com")).shouldBeFalse()
        (message.contains("minio", ignoreCase = true)).shouldBeFalse()
        (message.contains("private-bucket")).shouldBeFalse()
        (message.contains("prefix/private-note.md")).shouldBeFalse()
        (message.contains("403")).shouldBeFalse()
        (message.contains("Forbidden")).shouldBeFalse()
        (message.contains("HttpResponseException")).shouldBeFalse()
    }
}
