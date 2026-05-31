package com.lomo.data.s3

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



import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.CompleteMultipartUploadResponse
import aws.sdk.kotlin.services.s3.model.CreateMultipartUploadResponse
import aws.sdk.kotlin.services.s3.model.HeadObjectResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.PutObjectResponse
import aws.sdk.kotlin.services.s3.model.UploadPartResponse
import aws.sdk.kotlin.services.s3.model.`Object`
import aws.smithy.kotlin.runtime.time.Instant
import com.lomo.data.repository.S3ResolvedConfig
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: AwsSdkS3Client
 * - Behavior focus: paged S3 listings should reuse ListObjectsV2 summary fields without per-object HEAD calls, uploads should surface returned eTags, large file uploads should switch to multipart transfer with abort-on-failure, conditional write guards should reach both single-request and multipart upload paths, object metadata reads should use HEAD without downloading bodies, and deletions must hit the SDK synchronously so the sync state machine cannot record success before the network call confirms.
 * - Observable outcomes: listed S3RemoteObject values, absence of headObject calls during list, request routing across putObject vs multipart operations, conditional request headers, completed part metadata, abort behavior, returned S3PutObjectResult contents, getObjectMetadata results from headObject, single-key deleteObject calls reaching the SDK before the suspend returns, and multi-key deleteObjects sending one batched DeleteObjects request before the suspend returns.
 * - TDD proof: Fails before the fix because list(prefix, maxKeys = null) performs headObject per item, uploads do not surface the uploaded object's eTag, regular object metadata reads have no headObject-backed path, large file uploads still fall back to a single putObject request instead of multipart sequencing, multipart upload drops conditional headers before CompleteMultipartUpload, and deleteObject / deleteObjects only enqueue the keys until close() flushes them, so the SDK call has not yet happened when the suspend returns and any error propagates only at flush time.
 * - Excludes: live AWS transport behavior, sync planner logic, and repository orchestration.
 */
/*
 * Test Change Justification (deleteObject batching → synchronous deletes):
 * - Reason category: product/domain contract changed (lazy-flush deletes were the diagnosed defect being fixed).
 * - Old behavior/assertion being replaced: deleteObject() enqueued keys silently; only close() flushed them as a single batched DeleteObjects request, so coVerify(exactly = 0) { sdkClient.deleteObject(any()) } and coVerify(exactly = 1) { sdkClient.deleteObjects(...) } encoded that lazy contract.
 * - Why old assertion is no longer correct: under that contract the sync state machine recorded metadata as if the delete succeeded before the network call left the device; if close() flush failed, metadata diverged from remote with no recovery, which is the bug we are fixing.
 * - Coverage preserved by: `deleteObject issues a synchronous DeleteObject request before returning`, `deleteObject propagates SDK errors before returning so callers do not record success`, and `deleteObjects sends a single batched DeleteObjects request synchronously`.
 * - Why this is not fitting the test to the implementation: the lazy-flush behavior is the bug, not a contract; locking it in via test would protect the defect.
 */
class AwsSdkS3ClientTest : DataFunSpec() {
    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
            setUp()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("list uses summary fields across pages without head requests") { `list uses summary fields across pages without head requests`() }

        test("putObject returns uploaded eTag for metadata persistence") { `putObject returns uploaded eTag for metadata persistence`() }

        test("putObjectFile keeps small files on the single request path") { `putObjectFile keeps small files on the single request path`() }

        test("putObjectFile uses multipart upload for large files") { `putObjectFile uses multipart upload for large files`() }

        test("putObjectFile applies conditional headers to multipart completion") {
            `putObjectFile applies conditional headers to multipart completion`()
        }

        test("putObjectFile respects tuned multipart part concurrency") { `putObjectFile respects tuned multipart part concurrency`() }

        test("putObjectFile aborts multipart upload when a part fails") { `putObjectFile aborts multipart upload when a part fails`() }

        test("getObjectMetadata returns head metadata for a regular object") { `getObjectMetadata returns head metadata for a regular object`() }

        test("deleteObject issues a synchronous DeleteObject request before returning") { `deleteObject issues a synchronous DeleteObject request before returning`() }

        test("deleteObject propagates SDK errors before returning so callers do not record success") { `deleteObject propagates SDK errors before returning so callers do not record success`() }

        test("deleteObjects sends a single batched DeleteObjects request synchronously") { `deleteObjects sends a single batched DeleteObjects request synchronously`() }
    }


    private lateinit var tempFolder: KotestTemporaryFolder
    @MockK(relaxed = true)
    private lateinit var sdkClient: S3Client

    private val config =
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

    fun setUp() {
        MockKAnnotations.init(this)
        every { sdkClient.close() } returns Unit
    }

    private fun `list uses summary fields across pages without head requests`() =
        runTest {
            coEvery { sdkClient.headObject(any()) } throws
                AssertionError("list() should not call headObject() for listed objects")
            coEvery { sdkClient.listObjectsV2(any()) } returnsMany
                listOf(
                    listResponse(
                        objects =
                            listOf(
                                remoteSummary("vault/note.md", "etag-note", 30L),
                                remoteSummary("vault/folder/", null, 31L),
                            ),
                        nextContinuationToken = "page-2",
                    ),
                    listResponse(
                        objects =
                            listOf(
                                remoteSummary("vault/image.png", "etag-image", 40L),
                            ),
                    ),
                )

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val listed = client.list(prefix = "vault/", maxKeys = null)

            listed shouldBe listOf(
                    S3RemoteObject(
                        key = "vault/note.md",
                        eTag = "etag-note",
                        lastModified = 30_000L,
                        metadata = emptyMap(),
                    ),
                    S3RemoteObject(
                        key = "vault/image.png",
                        eTag = "etag-image",
                        lastModified = 40_000L,
                        metadata = emptyMap(),
                    ),
                )
            coVerify(exactly = 0) { sdkClient.headObject(any()) }
        }

    private fun `putObject returns uploaded eTag for metadata persistence`() =
        runTest {
            coEvery { sdkClient.putObject(any()) } returns
                PutObjectResponse {
                    eTag = "etag-uploaded"
                }

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val result =
                client.putSmallObject(
                    key = "vault/note.md",
                    bytes = "memo".toByteArray(),
                    contentType = "text/markdown; charset=utf-8",
                    metadata = mapOf("mtime" to "30"),
                )

            result shouldBe S3PutObjectResult(eTag = "etag-uploaded")
        }

    private fun `putObjectFile keeps small files on the single request path`() =
        runTest {
            val file = tempFile("small.bin", S3_MULTIPART_UPLOAD_THRESHOLD_BYTES - 1)
            coEvery { sdkClient.putObject(any()) } returns
                PutObjectResponse {
                    eTag = "etag-small"
                }
            coEvery { sdkClient.createMultipartUpload(any()) } throws
                AssertionError("Small files must not start multipart uploads")

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val result =
                client.putObjectFile(
                    key = "vault/small.bin",
                    file = file,
                    contentType = "application/octet-stream",
                    metadata = emptyMap(),
                )

            result shouldBe S3PutObjectResult(eTag = "etag-small")
            coVerify(exactly = 1) { sdkClient.putObject(match { it.key == "vault/small.bin" }) }
            coVerify(exactly = 0) { sdkClient.createMultipartUpload(any()) }
        }

    private fun `putObjectFile uses multipart upload for large files`() =
        runTest {
            val file = tempFile("large.bin", S3_MULTIPART_UPLOAD_THRESHOLD_BYTES + 1)
            coEvery { sdkClient.putObject(any()) } throws
                AssertionError("Large files must not use the single-request putObject path")
            coEvery { sdkClient.createMultipartUpload(any()) } returns
                CreateMultipartUploadResponse {
                    uploadId = "upload-123"
                }
            coEvery { sdkClient.uploadPart(any()) } answers {
                val request = firstArg<aws.sdk.kotlin.services.s3.model.UploadPartRequest>()
                UploadPartResponse {
                    eTag = "etag-part-${request.partNumber}"
                }
            }
            coEvery { sdkClient.completeMultipartUpload(any()) } returns
                CompleteMultipartUploadResponse {
                    eTag = "etag-complete"
                }

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val result =
                client.putObjectFile(
                    key = "vault/large.bin",
                    file = file,
                    contentType = "application/octet-stream",
                    metadata = mapOf("mtime" to "42"),
                )

            result shouldBe S3PutObjectResult(eTag = "etag-complete")
            coVerify(exactly = 0) { sdkClient.putObject(any()) }
            coVerify(exactly = 1) {
                sdkClient.createMultipartUpload(
                    match {
                        it.bucket == "bucket" &&
                            it.key == "vault/large.bin" &&
                            it.contentType == "application/octet-stream" &&
                            it.metadata == mapOf("mtime" to "42")
                    },
                )
            }
            coVerify(exactly = 2) {
                sdkClient.uploadPart(
                    match {
                        it.uploadId == "upload-123" &&
                            it.key == "vault/large.bin"
                    },
                )
            }
            coVerify(exactly = 1) {
                sdkClient.completeMultipartUpload(
                    match {
                        it.uploadId == "upload-123" &&
                            it.multipartUpload?.parts?.map { part -> part.partNumber to part.eTag } ==
                            listOf(
                                1 to "etag-part-1",
                                2 to "etag-part-2",
                            )
                    },
                )
            }
        }

    private fun `putObjectFile applies conditional headers to multipart completion`() =
        runTest {
            val file = tempFile("large-conditional.bin", S3_MULTIPART_UPLOAD_THRESHOLD_BYTES + 1)
            coEvery { sdkClient.putObject(any()) } throws
                AssertionError("Large files must not use the single-request putObject path")
            coEvery { sdkClient.createMultipartUpload(any()) } returns
                CreateMultipartUploadResponse {
                    uploadId = "upload-conditional"
                }
            coEvery { sdkClient.uploadPart(any()) } answers {
                val request = firstArg<aws.sdk.kotlin.services.s3.model.UploadPartRequest>()
                UploadPartResponse {
                    eTag = "etag-part-${request.partNumber}"
                }
            }
            coEvery { sdkClient.completeMultipartUpload(any()) } returns
                CompleteMultipartUploadResponse {
                    eTag = "etag-complete"
                }

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            client.putObjectFile(
                key = "vault/large-conditional.bin",
                file = file,
                contentType = "application/octet-stream",
                metadata = emptyMap(),
                ifMatch = "etag-old",
                ifNoneMatch = null,
            )

            coVerify(exactly = 1) {
                sdkClient.completeMultipartUpload(
                    match {
                        it.key == "vault/large-conditional.bin" &&
                            it.uploadId == "upload-conditional" &&
                            it.ifMatch == "etag-old" &&
                            it.ifNoneMatch == null
                    },
                )
            }
        }

    private fun `putObjectFile respects tuned multipart part concurrency`() =
        runTest {
            val file = tempFile("tuned-large.bin", S3_MULTIPART_UPLOAD_PART_SIZE_BYTES * 2 + 1)
            val inFlight = AtomicInteger(0)
            val peakConcurrency = AtomicInteger(0)
            coEvery { sdkClient.createMultipartUpload(any()) } returns
                CreateMultipartUploadResponse {
                    uploadId = "upload-tuned"
                }
            coEvery { sdkClient.uploadPart(any()) } answers {
                val concurrent = inFlight.incrementAndGet()
                peakConcurrency.accumulateAndGet(concurrent, ::maxOf)
                Thread.sleep(25)
                inFlight.decrementAndGet()
                val request = firstArg<aws.sdk.kotlin.services.s3.model.UploadPartRequest>()
                UploadPartResponse {
                    eTag = "etag-part-${request.partNumber}"
                }
            }
            coEvery { sdkClient.completeMultipartUpload(any()) } returns
                CompleteMultipartUploadResponse {
                    eTag = "etag-tuned"
                }

            val client =
                AwsSdkS3Client(
                    config = config,
                    client = sdkClient,
                    performanceProfile = com.lomo.data.repository.SyncPerformanceProfile(s3MultipartPartConcurrency = 1),
                )

            client.putObjectFile(
                key = "vault/tuned-large.bin",
                file = file,
                contentType = "application/octet-stream",
                metadata = emptyMap(),
            )

            peakConcurrency.get() shouldBe 1
        }

    private fun `putObjectFile aborts multipart upload when a part fails`() =
        runTest {
            val file = tempFile("broken.bin", S3_MULTIPART_UPLOAD_THRESHOLD_BYTES + 1)
            coEvery { sdkClient.createMultipartUpload(any()) } returns
                CreateMultipartUploadResponse {
                    uploadId = "upload-broken"
                }
            coEvery { sdkClient.uploadPart(match { it.partNumber == 1 }) } returns
                UploadPartResponse {
                    eTag = "etag-part-1"
                }
            coEvery { sdkClient.uploadPart(match { it.partNumber == 2 }) } throws IllegalStateException("boom")
            coEvery { sdkClient.abortMultipartUpload(any()) } returns
                aws.sdk.kotlin.services.s3.model.AbortMultipartUploadResponse {}

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            runCatching {
                client.putObjectFile(
                    key = "vault/broken.bin",
                    file = file,
                    contentType = "application/octet-stream",
                    metadata = emptyMap(),
                )
            }.onSuccess {
                throw AssertionError("Multipart failure should be rethrown")
            }.onFailure { error ->
                error.message shouldBe "boom"
            }

            coVerify(exactly = 1) {
                sdkClient.abortMultipartUpload(
                    match {
                        it.uploadId == "upload-broken" &&
                            it.key == "vault/broken.bin"
                    },
                )
            }
            coVerify(exactly = 0) { sdkClient.completeMultipartUpload(any()) }
        }

    private fun `getObjectMetadata returns head metadata for a regular object`() =
        runTest {
            coEvery { sdkClient.headObject(any()) } returns
                HeadObjectResponse {
                    eTag = "etag-note"
                    metadata =
                        mapOf(
                            "mtime" to "120",
                            "author" to "lomo",
                        )
                    lastModified = Instant.fromEpochSeconds(120)
                }

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val metadata = client.getObjectMetadata("vault/note.md")

            metadata shouldBe S3RemoteObject(
                    key = "vault/note.md",
                    eTag = "etag-note",
                    lastModified = 120_000L,
                    metadata =
                        mapOf(
                            "mtime" to "120",
                            "author" to "lomo",
                        ),
                )
        }

    private fun `deleteObject issues a synchronous DeleteObject request before returning`() =
        runTest {
            coEvery { sdkClient.deleteObject(any()) } returns
                aws.sdk.kotlin.services.s3.model.DeleteObjectResponse {}

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            client.deleteObject("vault/a.md")

            coVerify(exactly = 1) {
                sdkClient.deleteObject(
                    match { it.bucket == "bucket" && it.key == "vault/a.md" },
                )
            }
            coVerify(exactly = 0) { sdkClient.deleteObjects(any()) }
        }

    private fun `deleteObject propagates SDK errors before returning so callers do not record success`() =
        runTest {
            coEvery { sdkClient.deleteObject(any()) } throws IllegalStateException("boom")

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val error =
                runCatching {
                    client.deleteObject("vault/a.md")
                }.exceptionOrNull()

            error?.message shouldBe "boom"
        }

    private fun `deleteObjects sends a single batched DeleteObjects request synchronously`() =
        runTest {
            coEvery { sdkClient.deleteObjects(any()) } returns
                aws.sdk.kotlin.services.s3.model.DeleteObjectsResponse {}

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            client.deleteObjects(listOf("vault/a.md", "vault/b.md", "vault/c.md"))

            coVerify(exactly = 0) { sdkClient.deleteObject(any()) }
            coVerify(exactly = 1) {
                sdkClient.deleteObjects(
                    match {
                        it.bucket == "bucket" &&
                            it.delete?.objects?.mapNotNull { item -> item.key } ==
                            listOf("vault/a.md", "vault/b.md", "vault/c.md")
                    },
                )
            }
        }

    private fun listResponse(
        objects: List<`Object`>,
        nextContinuationToken: String? = null,
    ) = ListObjectsV2Response {
        contents = objects
        isTruncated = nextContinuationToken != null
        this.nextContinuationToken = nextContinuationToken
    }

    private fun remoteSummary(
        key: String,
        eTag: String?,
        epochSeconds: Long,
    ) = `Object` {
        this.key = key
        this.eTag = eTag
        lastModified = Instant.fromEpochSeconds(epochSeconds)
    }

    private fun tempFile(
        name: String,
        size: Long,
    ): File =
        tempFolder.newFile(name).apply {
            outputStream().use { output ->
                val chunk = ByteArray(8192) { index -> (index % 251).toByte() }
                var remaining = size
                while (remaining > 0) {
                    val toWrite = minOf(remaining, chunk.size.toLong()).toInt()
                    output.write(chunk, 0, toWrite)
                    remaining -= toWrite
                }
            }
        }
}
