package com.lomo.data.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.AbortMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompleteMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.CreateMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.UploadPartRequest
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.repository.SyncPerformanceProfile
import com.lomo.data.repository.S3ResolvedConfig
import com.lomo.data.repository.coercePositiveConcurrency
import com.lomo.domain.model.S3PathStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class S3RemoteObject(
    val key: String,
    val eTag: String?,
    val lastModified: Long?,
    val size: Long? = null,
    val metadata: Map<String, String>,
)

data class S3SmallObjectPayload(
    val key: String,
    val eTag: String?,
    val lastModified: Long?,
    val metadata: Map<String, String>,
    val bytes: ByteArray,
)

data class S3PutObjectResult(
    val eTag: String?,
    val conditionalWriteFailed: Boolean = false,
)

data class S3DeleteObjectsResult(
    val failedKeys: Set<String> = emptySet(),
)

data class S3RemoteListPage(
    val objects: List<S3RemoteObject>,
    val nextContinuationToken: String?,
)

internal const val S3_MULTIPART_UPLOAD_THRESHOLD_BYTES: Long = 8L * 1024L * 1024L
internal const val S3_MULTIPART_UPLOAD_PART_SIZE_BYTES: Long = 8L * 1024L * 1024L
internal const val S3_MULTIPART_PART_CONCURRENCY = 4
const val S3_SMALL_OBJECT_MAX_BYTES: Long = 256L * 1024L

fun interface LomoS3ClientFactory {
    fun create(config: S3ResolvedConfig): LomoS3Client
}

interface LomoS3ObjectReader {
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

    suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject

    /*
     * Behavior Contract:
     * Small-object payload helpers are reserved for memo text, metadata probes, and focused tests.
     * Remote object sync must use getObjectToFile/putObjectFile so large payloads never make
     * ByteArray the primary transport contract.
     */
    suspend fun getSmallObject(key: String): S3SmallObjectPayload

    suspend fun getSmallObject(
        key: String,
        maxBytes: Long,
    ): S3SmallObjectPayload = getSmallObject(key)
}

interface LomoS3ObjectWriter {
    suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
    ): S3PutObjectResult

    suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
    ): S3PutObjectResult

    suspend fun deleteObject(key: String)

    suspend fun deleteObjects(keys: List<String>): S3DeleteObjectsResult =
        error(
            "S3 batch delete is required at this client boundary; " +
                "implement deleteObjects without per-object fallback.",
        )
}

interface LomoS3Client : LomoS3ObjectReader, LomoS3ObjectWriter, AutoCloseable {
    suspend fun verifyAccess(prefix: String)
}

class AwsSdkS3ClientFactory(
    private val httpClientProvider: SyncHttpClientProvider,
    private val performanceTuner: com.lomo.data.repository.SyncPerformanceTuner,
) : LomoS3ClientFactory {
        override fun create(config: S3ResolvedConfig): LomoS3Client =
            AwsSdkS3Client(
                config = config,
                httpClientProvider = httpClientProvider,
                performanceProfile = performanceTuner.currentProfile(),
            )
    }

internal class AwsSdkS3Client(
    private val config: S3ResolvedConfig,
    private val client: S3Client,
    private val performanceProfile: SyncPerformanceProfile = SyncPerformanceProfile(),
) : LomoS3Client {
    internal constructor(
        config: S3ResolvedConfig,
        httpClientProvider: SyncHttpClientProvider = SyncHttpClientProvider(),
        performanceProfile: SyncPerformanceProfile = SyncPerformanceProfile(),
    ) : this(
        config = config,
        client = createSdkClient(config, performanceProfile, httpClientProvider),
        performanceProfile = performanceProfile,
    )

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

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload =
        getSmallObject(key, S3_SMALL_OBJECT_MAX_BYTES)

    override suspend fun getSmallObject(
        key: String,
        maxBytes: Long,
    ): S3SmallObjectPayload {
        val request =
            GetObjectRequest {
                bucket = config.bucket
                this.key = key
            }
        return client.getObject(request) { response ->
            val contentLength = response.contentLength
            if (contentLength != null && contentLength > maxBytes) {
                throw IOException("S3 object exceeds small-object limit: key=$key bytes=$contentLength max=$maxBytes")
            }
            S3SmallObjectPayload(
                key = key,
                eTag = response.eTag,
                lastModified = response.lastModified?.epochMilliseconds,
                metadata = response.metadata.orEmpty(),
                bytes = response.body?.toBoundedByteArray(maxBytes) ?: ByteArray(0),
            )
        }
    }

    override suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject {
        val request =
            GetObjectRequest {
                bucket = config.bucket
                this.key = key
            }
        destination.parentFile?.mkdirs()
        return client.getObject(request) { response ->
            destination.outputStream().use { output ->
                response.body?.toInputStream()?.use { input ->
                    input.copyTo(output)
                }
            }
            S3RemoteObject(
                key = key,
                eTag = response.eTag,
                lastModified = response.lastModified?.epochMilliseconds,
                size = response.contentLength ?: destination.length(),
                metadata = response.metadata.orEmpty(),
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

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        return try {
            val response =
                client.putObject(
                    PutObjectRequest {
                        bucket = config.bucket
                        this.key = key
                        this.contentType = contentType
                        this.metadata = metadata
                        this.ifMatch = ifMatch
                        this.ifNoneMatch = ifNoneMatch
                        body = ByteStream.fromBytes(bytes)
                    },
                )
            S3PutObjectResult(eTag = response.eTag)
        } catch (e: Exception) {
            if (e.isConditionalWriteFailedError()) {
                S3PutObjectResult(eTag = null, conditionalWriteFailed = true)
            } else {
                throw e
            }
        }
    }

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        if (file.length() < S3_MULTIPART_UPLOAD_THRESHOLD_BYTES) {
            return try {
                val response =
                    client.putObject(
                        PutObjectRequest {
                            bucket = config.bucket
                            this.key = key
                            this.contentType = contentType
                            this.metadata = metadata
                            this.ifMatch = ifMatch
                            this.ifNoneMatch = ifNoneMatch
                            body = file.asByteStream()
                        },
                    )
                S3PutObjectResult(eTag = response.eTag)
            } catch (e: Exception) {
                if (e.isConditionalWriteFailedError()) {
                    S3PutObjectResult(eTag = null, conditionalWriteFailed = true)
                } else {
                    throw e
                }
            }
        }
        return putMultipartObject(
            key = key,
            file = file,
            contentType = contentType,
            metadata = metadata,
            ifMatch = ifMatch,
            ifNoneMatch = ifNoneMatch,
        )
    }

    private suspend fun putMultipartObject(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        val uploadId =
            client.createMultipartUpload(
                CreateMultipartUploadRequest {
                    bucket = config.bucket
                    this.key = key
                    this.contentType = contentType
                    this.metadata = metadata
                },
            ).uploadId ?: error("S3 multipart upload started without uploadId for key=$key")
        try {
            val completedParts = uploadMultipartParts(key = key, file = file, uploadId = uploadId)
            val response =
                client.completeMultipartUpload(
                    CompleteMultipartUploadRequest {
                        bucket = config.bucket
                        this.key = key
                        this.uploadId = uploadId
                        multipartUpload =
                            CompletedMultipartUpload {
                                parts = completedParts
                            }
                        this.ifMatch = ifMatch
                        this.ifNoneMatch = ifNoneMatch
                    },
                )
            return S3PutObjectResult(eTag = response.eTag)
        } catch (error: Exception) {
            runCatching {
                client.abortMultipartUpload(
                    AbortMultipartUploadRequest {
                        bucket = config.bucket
                        this.key = key
                        this.uploadId = uploadId
                    },
                )
            }
            if (error.isConditionalWriteFailedError()) {
                return S3PutObjectResult(eTag = null, conditionalWriteFailed = true)
            } else {
                throw error
            }
        }
    }

    private suspend fun uploadMultipartParts(
        key: String,
        file: File,
        uploadId: String,
    ): List<CompletedPart> {
        val totalParts = ((file.length() + S3_MULTIPART_UPLOAD_PART_SIZE_BYTES - 1) /
            S3_MULTIPART_UPLOAD_PART_SIZE_BYTES).toInt()
        if (totalParts <= 1) {
            val partSize = minOf(S3_MULTIPART_UPLOAD_PART_SIZE_BYTES, file.length())
            val response =
                client.uploadPart(
                    UploadPartRequest {
                        bucket = config.bucket
                        this.key = key
                        this.uploadId = uploadId
                        partNumber = 1
                        body = file.asByteStream(0L, partSize - 1)
                    },
                )
            return listOf(
                CompletedPart {
                    partNumber = 1
                    eTag = response.eTag
                },
            )
        }
        val limiter = Semaphore(performanceProfile.s3MultipartPartConcurrency.coercePositiveConcurrency())
        return coroutineScope {
            (1..totalParts).map { partNum ->
                async {
                    limiter.withPermit {
                        val offset = (partNum - 1).toLong() * S3_MULTIPART_UPLOAD_PART_SIZE_BYTES
                        val partSize = minOf(S3_MULTIPART_UPLOAD_PART_SIZE_BYTES, file.length() - offset)
                        val endInclusive = offset + partSize - 1
                        val response =
                            client.uploadPart(
                                UploadPartRequest {
                                    bucket = config.bucket
                                    this.key = key
                                    this.uploadId = uploadId
                                    partNumber = partNum
                                    body = file.asByteStream(offset, endInclusive)
                                },
                            )
                        CompletedPart {
                            partNumber = partNum
                            eTag = response.eTag
                        }
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun deleteObject(key: String) {
        client.deleteObject(
            DeleteObjectRequest {
                bucket = config.bucket
                this.key = key
            },
        )
    }

    override suspend fun deleteObjects(keys: List<String>): S3DeleteObjectsResult {
        if (keys.isEmpty()) return S3DeleteObjectsResult()
        val failedKeys = mutableSetOf<String>()
        for (chunk in keys.chunked(S3_BULK_DELETE_BATCH_SIZE)) {
            failedKeys += submitDeleteBatch(chunk).failedKeys
        }
        return S3DeleteObjectsResult(failedKeys = failedKeys)
    }

    override fun close() {
        client.close()
    }

    private suspend fun submitDeleteBatch(keys: List<String>): S3DeleteObjectsResult {
        if (keys.isEmpty()) return S3DeleteObjectsResult()
        if (keys.size == 1) {
            client.deleteObject(
                DeleteObjectRequest {
                    bucket = config.bucket
                    this.key = keys.single()
                },
            )
            return S3DeleteObjectsResult()
        }
        val response =
            client.deleteObjects(
            DeleteObjectsRequest {
                bucket = config.bucket
                delete =
                    Delete {
                        quiet = true
                        objects =
                            keys.map { key ->
                                ObjectIdentifier {
                                    this.key = key
                                }
                            }
                    }
            },
        )
        return S3DeleteObjectsResult(
            failedKeys = response.errors.orEmpty().mapNotNull { error -> error.key }.toSet(),
        )
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

private fun ByteStream.toBoundedByteArray(maxBytes: Long): ByteArray {
    val maxSize = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val output = ByteArrayOutputStream(minOf(maxSize, DEFAULT_SMALL_OBJECT_BUFFER_BYTES))
    toInputStream().use { input ->
        val buffer = ByteArray(DEFAULT_SMALL_OBJECT_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            total += read
            if (total > maxSize) {
                throw IOException("S3 object exceeds small-object limit: bytes=$total max=$maxBytes")
            }
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}

private const val S3_BULK_DELETE_BATCH_SIZE = 1000
private const val DEFAULT_SMALL_OBJECT_BUFFER_BYTES = 8 * 1024

private fun Throwable.isConditionalWriteFailedError(): Boolean {
    val diagnostic =
        buildString {
            message?.let(::appendLine)
            (this@isConditionalWriteFailedError as? ServiceException)?.sdkErrorMetadata?.let { metadata ->
                metadata.errorCode?.let(::appendLine)
                metadata.errorMessage?.let(::appendLine)
                appendLine(metadata.protocolResponse.summary)
            }
        }
    return diagnostic.contains("412") ||
        diagnostic.contains("PreconditionFailed", ignoreCase = true)
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

private fun createSdkClient(
    config: S3ResolvedConfig,
    performanceProfile: SyncPerformanceProfile,
    httpClientProvider: SyncHttpClientProvider,
): S3Client {
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
        httpClient =
            OkHttpEngine(
                httpClientProvider.s3Client(
                    maxRequests = performanceProfile.s3MaxConnections,
                    maxRequestsPerHost = performanceProfile.s3MaxConnectionsPerHost,
                ),
            )
    }
}
