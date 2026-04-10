package com.lomo.data.s3

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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/*
 * Test Contract:
 * - Unit under test: AwsSdkS3Client
 * - Behavior focus: paged S3 listings should reuse ListObjectsV2 summary fields without per-object HEAD calls, uploads should surface returned eTags, large file uploads should switch to multipart transfer with abort-on-failure, and object metadata reads should use HEAD without downloading bodies.
 * - Observable outcomes: listed S3RemoteObject values, absence of headObject calls during list, request routing across putObject vs multipart operations, completed part metadata, abort behavior, returned S3PutObjectResult contents, and getObjectMetadata results from headObject.
 * - Red phase: Fails before the fix because list(prefix, maxKeys = null) performs headObject per item, uploads do not surface the uploaded object's eTag, regular object metadata reads have no headObject-backed path, and large file uploads still fall back to a single putObject request instead of multipart sequencing.
 * - Excludes: live AWS transport behavior, sync planner logic, and repository orchestration.
 */
class AwsSdkS3ClientTest {
    @get:Rule val tempFolder = TemporaryFolder()

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

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { sdkClient.close() } returns Unit
    }

    @Test
    fun `list uses summary fields across pages without head requests`() =
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

            assertEquals(
                listOf(
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
                ),
                listed,
            )
            coVerify(exactly = 0) { sdkClient.headObject(any()) }
        }

    @Test
    fun `putObject returns uploaded eTag for metadata persistence`() =
        runTest {
            coEvery { sdkClient.putObject(any()) } returns
                PutObjectResponse {
                    eTag = "etag-uploaded"
                }

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val result =
                client.putObject(
                    key = "vault/note.md",
                    bytes = "memo".toByteArray(),
                    contentType = "text/markdown; charset=utf-8",
                    metadata = mapOf("mtime" to "30"),
                )

            assertEquals(S3PutObjectResult(eTag = "etag-uploaded"), result)
        }

    @Test
    fun `putObjectFile keeps small files on the single request path`() =
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

            assertEquals(S3PutObjectResult(eTag = "etag-small"), result)
            coVerify(exactly = 1) { sdkClient.putObject(match { it.key == "vault/small.bin" }) }
            coVerify(exactly = 0) { sdkClient.createMultipartUpload(any()) }
        }

    @Test
    fun `putObjectFile uses multipart upload for large files`() =
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

            assertEquals(S3PutObjectResult(eTag = "etag-complete"), result)
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

    @Test
    fun `putObjectFile aborts multipart upload when a part fails`() =
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
                assertEquals("boom", error.message)
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

    @Test
    fun `getObjectMetadata returns head metadata for a regular object`() =
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

            assertEquals(
                S3RemoteObject(
                    key = "vault/note.md",
                    eTag = "etag-note",
                    lastModified = 120_000L,
                    metadata =
                        mapOf(
                            "mtime" to "120",
                            "author" to "lomo",
                        ),
                ),
                metadata,
            )
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
