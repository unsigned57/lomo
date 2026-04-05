package com.lomo.data.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.HeadObjectResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.PutObjectResponse
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
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: AwsSdkS3Client
 * - Behavior focus: paged S3 listings should reuse ListObjectsV2 summary fields without per-object HEAD calls, uploads should surface the returned eTag for metadata caching, and manifest probing should expose HEAD metadata without downloading the object body.
 * - Observable outcomes: listed S3RemoteObject values, absence of headObject calls during list, returned S3PutObjectResult contents, and getObjectMetadata results from headObject.
 * - Red phase: Fails before the fix because list(prefix, maxKeys = null) performs headObject per item, putObject does not return the uploaded object's eTag, and manifest revision probing has no headObject-backed metadata path.
 * - Excludes: live AWS transport behavior, sync planner logic, and repository orchestration.
 */
class AwsSdkS3ClientTest {
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
    fun `getObjectMetadata returns head metadata for manifest revision probing`() =
        runTest {
            coEvery { sdkClient.headObject(any()) } returns
                HeadObjectResponse {
                    eTag = "etag-manifest"
                    metadata =
                        mapOf(
                            "lomo-sync-revision" to "12",
                            "lomo-sync-generated-at" to "120",
                        )
                    lastModified = Instant.fromEpochSeconds(120)
                }

            val client = AwsSdkS3Client(config = config, client = sdkClient)

            val metadata = client.getObjectMetadata("vault/.lomo-sync-manifest-v2.json")

            assertEquals(
                S3RemoteObject(
                    key = "vault/.lomo-sync-manifest-v2.json",
                    eTag = "etag-manifest",
                    lastModified = 120_000L,
                    metadata =
                        mapOf(
                            "lomo-sync-revision" to "12",
                            "lomo-sync-generated-at" to "120",
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
}
