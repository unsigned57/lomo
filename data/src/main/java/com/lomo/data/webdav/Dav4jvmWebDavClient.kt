package com.lomo.data.webdav

import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.MultiResponseCallback
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.ResponseCallback
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.GetETag
import at.bitfire.dav4jvm.property.GetLastModified
import at.bitfire.dav4jvm.property.ResourceType
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Response as OkHttpResponse

@Singleton
class Dav4jvmWebDavClientFactory
    @Inject
    constructor() : WebDavClientFactory {
        override fun create(
            endpointUrl: String,
            username: String,
            password: String,
        ): WebDavClient = Dav4jvmWebDavClient(endpointUrl = endpointUrl, username = username, password = password)
    }

class Dav4jvmWebDavClient(
    endpointUrl: String,
    username: String,
    password: String,
) : WebDavClient {
    private val rootUrl = endpointUrl.toHttpUrl()
    private val rootPathSegments = rootUrl.pathSegments.filter { it.isNotEmpty() }
    private val logger = Logger.getLogger(TAG)
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                chain.proceed(request)
            }.build()
    private val rootCollection = DavCollection(httpClient, rootUrl, logger)

    override fun ensureDirectory(path: String) {
        val targetUrl = resolve(path)
        val resource = DavResource(httpClient, targetUrl, logger)
        try {
            resource.propfind(0, ResourceType.NAME, callback = emptyMultiResponseCallback())
        } catch (error: HttpException) {
            if (error.code != 404) throw error
            resource.mkCol(null, emptyResponseCallback())
        }
    }

    override fun list(path: String): List<WebDavRemoteResource> {
        val targetUrl = resolve(path)
        val collection = DavCollection(httpClient, targetUrl, logger)
        val resources = mutableListOf<WebDavRemoteResource>()
        try {
            collection.propfind(
                1,
                ResourceType.NAME,
                GetETag.NAME,
                GetLastModified.NAME,
                callback =
                    object : MultiResponseCallback {
                        override fun onResponse(
                            response: Response,
                            relation: Response.HrefRelation,
                        ) {
                            if (!response.isSuccess() || relation == Response.HrefRelation.SELF) {
                                return
                            }
                            val relativePath = relativeToRoot(response.href.toString()) ?: return
                            resources +=
                                WebDavRemoteResource(
                                    path = relativePath,
                                    isDirectory = response.href.toString().endsWith("/"),
                                    etag = response.get(GetETag::class.java)?.eTag,
                                    lastModified = response.get(GetLastModified::class.java)?.lastModified,
                                )
                        }
                    },
            )
        } catch (error: HttpException) {
            if (error.code != 404) throw error
            return emptyList()
        }
        return resources
    }

    override fun get(path: String): WebDavRemoteFile {
        val normalizedPath = normalizePath(path)
        val resource = DavResource(httpClient, resolve(normalizedPath), logger)
        var file: WebDavRemoteFile? = null
        resource.get(
            "text/markdown",
            Headers.headersOf("Accept-Encoding", "identity"),
            object : ResponseCallback {
                override fun onResponse(response: OkHttpResponse) {
                    response.use { httpResponse ->
                        file =
                            WebDavRemoteFile(
                                path = normalizedPath,
                                bytes = httpResponse.body.bytes(),
                                etag = httpResponse.header("ETag"),
                                lastModified = parseHttpDate(httpResponse.header("Last-Modified")),
                            )
                    }
                }
            },
        )
        return file ?: throw IOException("Empty WebDAV response for $normalizedPath")
    }

    override fun put(
        path: String,
        bytes: ByteArray,
        contentType: String,
        lastModifiedHint: Long?,
    ) {
        val normalizedPath = normalizePath(path)
        ensureDirectory(parentPath(normalizedPath))
        val requestBody = bytes.toRequestBody(contentType.toMediaType())
        try {
            putOnce(normalizedPath, requestBody)
        } catch (error: HttpException) {
            if (error.code != 404) throw error
            ensureDirectory(parentPath(normalizedPath))
            putOnce(normalizedPath, requestBody)
        }
    }

    override fun delete(path: String) {
        DavResource(httpClient, resolve(path), logger).delete(
            null,
            null,
            emptyResponseCallback(),
        )
    }

    override fun testConnection() {
        rootCollection.propfind(0, ResourceType.NAME, callback = emptyMultiResponseCallback())
    }

    private fun putOnce(
        path: String,
        requestBody: RequestBody,
    ) {
        DavResource(httpClient, resolve(path), logger).put(
            requestBody,
            null,
            null,
            false,
            emptyResponseCallback(),
        )
    }

    private fun resolve(path: String): HttpUrl {
        val trimmed = normalizePath(path).takeIf { it.isNotEmpty() } ?: return rootUrl
        val builder = rootUrl.newBuilder()
        trimmed.split('/').forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun normalizePath(path: String): String = path.trim('/').trim()

    private fun parentPath(path: String): String = normalizePath(path).substringBeforeLast('/', "")

    private fun relativeToRoot(href: String): String? {
        val resolvedUrl = rootUrl.resolve(href) ?: href.toHttpUrlOrNull() ?: return null
        if (
            resolvedUrl.scheme != rootUrl.scheme ||
            resolvedUrl.host != rootUrl.host ||
            resolvedUrl.port != rootUrl.port
        ) {
            return null
        }
        val targetPathSegments = resolvedUrl.pathSegments.filter { it.isNotEmpty() }
        if (targetPathSegments.size < rootPathSegments.size) {
            return null
        }
        if (targetPathSegments.subList(0, rootPathSegments.size) != rootPathSegments) {
            return null
        }
        return targetPathSegments.drop(rootPathSegments.size).joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun parseHttpDate(value: String?): Long? =
        runCatching {
            value ?: return null
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()

    private fun emptyResponseCallback() =
        object : ResponseCallback {
            override fun onResponse(response: OkHttpResponse) {
                response.close()
            }
        }

    private fun emptyMultiResponseCallback() =
        object : MultiResponseCallback {
            override fun onResponse(
                response: Response,
                relation: Response.HrefRelation,
            ) = Unit
        }

    private companion object {
        private const val TAG = "Dav4jvmWebDavClient"
    }
}
