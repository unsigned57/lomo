package com.lomo.data.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.lomo.data.repository.S3ResolvedConfig
import com.lomo.domain.model.S3PathStyle
import javax.inject.Inject

data class S3RemoteObject(
    val key: String,
    val eTag: String?,
    val lastModified: Long?,
    val size: Long? = null,
    val metadata: Map<String, String>,
)

data class S3RemoteObjectPayload(
    val key: String,
    val eTag: String?,
    val lastModified: Long?,
    val metadata: Map<String, String>,
    val bytes: ByteArray,
)

data class S3PutObjectResult(
    val eTag: String?,
)

data class S3RemoteListPage(
    val objects: List<S3RemoteObject>,
    val nextContinuationToken: String?,
)

fun interface LomoS3ClientFactory {
    fun create(config: S3ResolvedConfig): LomoS3Client
}

interface LomoS3Client : AutoCloseable {
    suspend fun verifyAccess(prefix: String)

    suspend fun getObjectMetadata(key: String): S3RemoteObject? = null

    suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage =
        if (continuationToken == null) {
            S3RemoteListPage(
                objects = list(prefix = prefix, maxKeys = maxKeys),
                nextContinuationToken = null,
            )
        } else {
            S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
        }

    suspend fun listKeys(
        prefix: String,
        maxKeys: Int? = null,
    ): List<String> = list(prefix = prefix, maxKeys = maxKeys).map(S3RemoteObject::key)

    suspend fun list(
        prefix: String,
        maxKeys: Int? = null,
    ): List<S3RemoteObject>

    suspend fun getObject(key: String): S3RemoteObjectPayload

    suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult

    suspend fun deleteObject(key: String)
}

class AwsSdkS3ClientFactory
    @Inject
    constructor() : LomoS3ClientFactory {
        override fun create(config: S3ResolvedConfig): LomoS3Client = AwsSdkS3Client(config)
    }

internal class AwsSdkS3Client(
    private val config: S3ResolvedConfig,
    private val client: S3Client = createSdkClient(config),
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) {
        client.listObjectsV2(
            ListObjectsV2Request {
                bucket = config.bucket
                this.prefix = prefix.takeIf(String::isNotBlank)
                maxKeys = 1
            },
        )
    }

    override suspend fun listKeys(
        prefix: String,
        maxKeys: Int?,
    ): List<String> {
        val requestPrefix = prefix.takeIf(String::isNotBlank)
        val keys = mutableListOf<String>()
        listObjects(requestPrefix, maxKeys) { key, _, _, _ ->
            keys += key
        }
        return keys
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        val requestPrefix = prefix.takeIf(String::isNotBlank)
        val objects = mutableListOf<S3RemoteObject>()
        listObjects(requestPrefix, maxKeys) { key, eTag, lastModified, size ->
            objects +=
                S3RemoteObject(
                    key = key,
                    eTag = eTag,
                    lastModified = lastModified,
                    size = size,
                    metadata = emptyMap(),
                )
        }
        return objects
    }

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        val request =
            GetObjectRequest {
                bucket = config.bucket
                this.key = key
            }
        return client.getObject(request) { response ->
            S3RemoteObjectPayload(
                key = key,
                eTag = response.eTag,
                lastModified = response.lastModified?.epochMilliseconds,
                metadata = response.metadata.orEmpty(),
                bytes = response.body?.toByteArray() ?: ByteArray(0),
            )
        }
    }

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        val response =
            try {
                client.headObject(
                    HeadObjectRequest {
                        bucket = config.bucket
                        this.key = key
                    },
                )
            } catch (error: ServiceException) {
                if (error.isMissingObjectError()) {
                    return null
                }
                throw error
            }
        return S3RemoteObject(
            key = key,
            eTag = response.eTag,
            lastModified = response.lastModified?.epochMilliseconds,
            size = response.contentLength,
            metadata = response.metadata.orEmpty(),
        )
    }

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage {
        val requestPrefix = prefix.takeIf(String::isNotBlank)
        val page =
            client.listObjectsV2(
                ListObjectsV2Request {
                    bucket = config.bucket
                    this.prefix = requestPrefix
                    this.continuationToken = continuationToken
                    this.maxKeys = maxKeys
                },
            )
        return S3RemoteListPage(
            objects =
                page.contents.orEmpty()
                    .mapNotNull { summary ->
                        val key = summary.key ?: return@mapNotNull null
                        if (key.endsWith('/')) return@mapNotNull null
                        S3RemoteObject(
                            key = key,
                            eTag = summary.eTag,
                            lastModified = summary.lastModified?.epochMilliseconds,
                            size = summary.size,
                            metadata = emptyMap(),
                        )
                    },
            nextContinuationToken = page.nextContinuationToken,
        )
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        val response =
            client.putObject(
                PutObjectRequest {
                    bucket = config.bucket
                    this.key = key
                    this.contentType = contentType
                    this.metadata = metadata
                    body = ByteStream.fromBytes(bytes)
                },
            )
        return S3PutObjectResult(eTag = response.eTag)
    }

    override suspend fun deleteObject(key: String) {
        client.deleteObject(
            DeleteObjectRequest {
                bucket = config.bucket
                this.key = key
            },
        )
    }

    override fun close() {
        client.close()
    }

    private suspend fun listObjects(
        requestPrefix: String?,
        maxKeys: Int?,
        onObject: (key: String, eTag: String?, lastModified: Long?, size: Long?) -> Unit,
    ) {
        var continuationToken: String? = null
        var remaining = maxKeys
        do {
            val page =
                client.listObjectsV2(
                    ListObjectsV2Request {
                        bucket = config.bucket
                        prefix = requestPrefix
                        this.maxKeys = remaining
                        this.continuationToken = continuationToken
                    },
                )
            page.contents.orEmpty().forEach { summary ->
                val key = summary.key ?: return@forEach
                if (key.endsWith('/')) return@forEach
                onObject(key, summary.eTag, summary.lastModified?.epochMilliseconds, summary.size)
            }
            continuationToken = page.nextContinuationToken
            remaining =
                remaining?.let { limit ->
                    (limit - page.contents.orEmpty().count { summary ->
                        val key = summary.key
                        key != null && !key.endsWith('/')
                    }).coerceAtLeast(0)
                }
        } while (continuationToken != null && (remaining == null || remaining > 0))
    }
}

private fun Throwable.isMissingObjectError(): Boolean {
    val diagnostic =
        buildString {
            message?.let(::appendLine)
            (this@isMissingObjectError as? ServiceException)?.sdkErrorMetadata?.let { metadata ->
                metadata.errorCode?.let(::appendLine)
                metadata.errorMessage?.let(::appendLine)
                appendLine(metadata.protocolResponse.summary)
            }
        }
    return diagnostic.contains("404") ||
        diagnostic.contains("NoSuchKey", ignoreCase = true) ||
        diagnostic.contains("NotFound", ignoreCase = true)
}

private fun createSdkClient(config: S3ResolvedConfig): S3Client {
    val endpoint = Url.parse(config.endpointUrl)
    return S3Client {
        region = config.region
        endpointUrl = endpoint
        credentialsProvider =
            StaticCredentialsProvider {
                accessKeyId = config.accessKeyId
                secretAccessKey = config.secretAccessKey
                sessionToken = config.sessionToken
            }
        forcePathStyle =
            when (config.pathStyle) {
                S3PathStyle.PATH_STYLE -> true
                S3PathStyle.VIRTUAL_HOSTED -> false
                S3PathStyle.AUTO -> !endpoint.host.toString().contains("amazonaws.com", ignoreCase = true)
            }
    }
}
