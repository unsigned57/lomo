package com.lomo.data.webdav

import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.repository.SyncPerformanceTuner
import com.lomo.data.repository.webDavMaxRequests
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

class OkHttpWebDavClientFactory(
    private val httpClientProvider: SyncHttpClientProvider,
    private val performanceTuner: SyncPerformanceTuner,
) : WebDavClientFactory {
    private val clients = ConcurrentHashMap<WebDavClientKey, WebDavClient>()

    override fun create(
        endpointUrl: String,
        username: String,
        password: String,
    ): WebDavClient =
        clients.getOrPut(WebDavClientKey(endpointUrl, username, password)) {
            val profile = performanceTuner.currentProfile()
            OkHttpWebDavClient(
                endpointUrl = endpointUrl,
                username = username,
                password = password,
                httpClient =
                    httpClientProvider.webDavClient(
                        username = username,
                        password = password,
                        maxRequests = profile.webDavMaxRequests(),
                        maxRequestsPerHost = profile.webDavMaxRequests(),
                    ),
            )
        }

    fun invalidate(endpointUrl: String, username: String) {
        clients.keys.removeAll { key -> key.endpointUrl == endpointUrl && key.username == username }
    }
}

class OkHttpWebDavClient(
    endpointUrl: String,
    username: String,
    password: String,
    private val httpClient: OkHttpClient = SyncHttpClientProvider().webDavClient(username, password),
) : WebDavClient {
    private val rootUrl = endpointUrl.toHttpUrl()
    private val multiStatusParser = WebDavMultiStatusParser(rootUrl)

    override fun ensureDirectory(path: String) {
        val targetUrl = resolve(path)
        when (propfind(targetUrl, depth = 0)) {
            is PropfindResult.Present -> Unit
            PropfindResult.Missing -> executeWithoutBody(webDavRequest(targetUrl, METHOD_MKCOL))
        }
    }

    override fun list(path: String): List<WebDavRemoteResource> =
        when (val result = propfind(resolve(path), depth = 1)) {
            PropfindResult.Missing -> emptyList()
            is PropfindResult.Present -> multiStatusParser.parse(result.body)
        }

    override fun getSmallFile(path: String): WebDavSmallRemoteFile =
        getSmallFile(path, WEBDAV_SMALL_FILE_MAX_BYTES)

    override fun getSmallFile(
        path: String,
        maxBytes: Long,
    ): WebDavSmallRemoteFile {
        val normalizedPath = normalizePath(path)
        val request =
            Request.Builder()
                .url(resolve(normalizedPath))
                .get()
                .header("Accept", "text/markdown")
                .header("Accept-Encoding", "identity")
                .build()
        return httpClient.newCall(request).execute().use { response ->
            response.requireSuccessful("GET", normalizedPath)
            val responseBody = response.body
            val contentLength = responseBody.contentLength()
            if (contentLength > maxBytes) {
                throw IOException(
                    "WebDAV file exceeds small-file limit: path=$normalizedPath " +
                        "bytes=$contentLength max=$maxBytes",
                )
            }
            WebDavSmallRemoteFile(
                path = normalizedPath,
                bytes = responseBody.byteStream().use { input -> input.readBoundedBytes(maxBytes, normalizedPath) },
                etag = response.header("ETag"),
                lastModified = parseHttpDate(response.header("Last-Modified")),
            )
        }
    }

    override fun getToFile(
        path: String,
        destination: File,
    ): WebDavRemoteResource {
        val normalizedPath = normalizePath(path)
        val request =
            Request.Builder()
                .url(resolve(normalizedPath))
                .get()
                .header("Accept", OCTET_STREAM)
                .header("Accept-Encoding", "identity")
                .build()
        destination.parentFile?.mkdirs()
        return httpClient.newCall(request).execute().use { response ->
            response.requireSuccessful("GET", normalizedPath)
            val responseBody = response.body
            responseBody.byteStream().use { input ->
                destination.outputStream().use(input::copyTo)
            }
            WebDavRemoteResource(
                path = normalizedPath,
                isDirectory = false,
                etag = response.header("ETag"),
                lastModified = parseHttpDate(response.header("Last-Modified")),
                size = responseBody.contentLength().takeIf { it >= 0L } ?: destination.length(),
            )
        }
    }

    override fun putSmallFile(
        path: String,
        bytes: ByteArray,
        contentType: String,
        lastModifiedHint: Long?,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) {
        putWithParentRecovery(
            path = path,
            requestBody = bytes.toRequestBody(contentType.toMediaType()),
            expectedEtag = expectedEtag,
            requireAbsent = requireAbsent,
        )
    }

    override fun putFile(
        path: String,
        file: File,
        contentType: String,
        lastModifiedHint: Long?,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) {
        putWithParentRecovery(
            path = path,
            requestBody = file.asRequestBody(contentType.toMediaType()),
            expectedEtag = expectedEtag,
            requireAbsent = requireAbsent,
        )
    }

    override fun delete(
        path: String,
        expectedEtag: String?,
    ) {
        val normalizedPath = normalizePath(path)
        val builder = webDavRequest(resolve(normalizedPath), METHOD_DELETE)
        expectedEtag?.let { builder.header("If-Match", it) }
        executeWithoutBody(builder)
    }

    override fun move(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean,
    ) {
        executeRemoteCopyOrMove(METHOD_MOVE, sourcePath, targetPath, overwrite)
    }

    override fun copy(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean,
    ) {
        executeRemoteCopyOrMove(METHOD_COPY, sourcePath, targetPath, overwrite)
    }

    override fun testConnection() {
        when (propfind(rootUrl, depth = 0)) {
            is PropfindResult.Present -> Unit
            PropfindResult.Missing -> throw WebDavHttpException(METHOD_PROPFIND, rootUrl.toString(), HTTP_NOT_FOUND)
        }
    }

    private fun putWithParentRecovery(
        path: String,
        requestBody: RequestBody,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) {
        val normalizedPath = normalizePath(path)
        try {
            putOnce(normalizedPath, requestBody, expectedEtag, requireAbsent)
        } catch (error: WebDavHttpException) {
            if (error.statusCode != HTTP_NOT_FOUND) throw error
            ensureDirectory(parentPath(normalizedPath))
            putOnce(normalizedPath, requestBody, expectedEtag, requireAbsent)
        }
    }

    private fun putOnce(
        path: String,
        requestBody: RequestBody,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) {
        val builder = webDavRequest(resolve(path), METHOD_PUT, requestBody)
        expectedEtag?.let { builder.header("If-Match", it) }
        if (requireAbsent) {
            builder.header("If-None-Match", "*")
        }
        executeWithoutBody(builder)
    }

    private fun propfind(
        url: HttpUrl,
        depth: Int,
    ): PropfindResult {
        val request =
            webDavRequest(url, METHOD_PROPFIND, PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", depth.toString())
                .build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) {
                PropfindResult.Missing
            } else {
                response.requireSuccessful(METHOD_PROPFIND, url.toString())
                PropfindResult.Present(response.body.bytes())
            }
        }
    }

    private fun resolve(path: String): HttpUrl {
        val trimmed = normalizePath(path).takeIf(String::isNotEmpty) ?: return rootUrl
        return rootUrl.newBuilder().apply {
            trimmed.split('/').forEach(::addPathSegment)
        }.build()
    }

    private fun normalizePath(path: String): String = path.trim('/').trim()

    private fun parentPath(path: String): String = normalizePath(path).substringBeforeLast('/', "")

    private fun executeRemoteCopyOrMove(
        method: String,
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean,
    ) {
        val builder =
            webDavRequest(resolve(sourcePath), method)
                .header("Destination", resolve(targetPath).toString())
                .header("Overwrite", if (overwrite) OVERWRITE_TRUE else OVERWRITE_FALSE)
        executeWithoutBody(builder)
    }

    private fun executeWithoutBody(builder: Request.Builder) {
        val request = builder.build()
        httpClient.newCall(request).execute().use { response ->
            response.requireSuccessful(request.method, request.url.toString())
        }
    }

    private fun webDavRequest(
        url: HttpUrl,
        method: String,
        body: RequestBody? = null,
    ): Request.Builder = Request.Builder().url(url).method(method, body)

    private companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private const val HTTP_NOT_FOUND = 404
        private const val METHOD_COPY = "COPY"
        private const val METHOD_DELETE = "DELETE"
        private const val METHOD_MKCOL = "MKCOL"
        private const val METHOD_MOVE = "MOVE"
        private const val METHOD_PROPFIND = "PROPFIND"
        private const val METHOD_PUT = "PUT"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val OVERWRITE_FALSE = "F"
        private const val OVERWRITE_TRUE = "T"
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype />
    <d:getetag />
    <d:getlastmodified />
    <d:getcontentlength />
  </d:prop>
</d:propfind>"""
    }
}

private class WebDavMultiStatusParser(
    private val rootUrl: HttpUrl,
) {
    private val rootPathSegments = rootUrl.pathSegments.filter(String::isNotEmpty)

    fun parse(body: ByteArray): List<WebDavRemoteResource> {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(body))
        val responses =
            document.getElementsByTagNameNS(
                OkHttpWebDavXml.DAV_NAMESPACE,
                OkHttpWebDavXml.ELEMENT_RESPONSE,
            )
        return buildList {
            for (index in 0 until responses.length) {
                val resource = (responses.item(index) as? Element)?.toRemoteResource() ?: continue
                add(resource)
            }
        }
    }

    private fun Element.toRemoteResource(): WebDavRemoteResource? {
        val href = firstDavElement(OkHttpWebDavXml.ELEMENT_HREF)?.textContent?.trim().orEmpty()
        val relativePath = relativeToRoot(href) ?: return null
        val properties = successfulProperties() ?: return null
        return WebDavRemoteResource(
            path = relativePath,
            isDirectory = properties.hasDavDescendant(OkHttpWebDavXml.ELEMENT_COLLECTION),
            etag =
                properties
                    .firstDavElement(OkHttpWebDavXml.ELEMENT_GET_ETAG)
                    ?.textContent
                    ?.trim()
                    ?.takeIf(String::isNotEmpty),
            lastModified =
                parseHttpDate(
                    properties
                        .firstDavElement(OkHttpWebDavXml.ELEMENT_GET_LAST_MODIFIED)
                        ?.textContent
                        ?.trim(),
                ),
            size =
                properties
                    .firstDavElement(OkHttpWebDavXml.ELEMENT_GET_CONTENT_LENGTH)
                    ?.textContent
                    ?.trim()
                    ?.toLongOrNull(),
        )
    }

    private fun relativeToRoot(href: String): String? {
        val resolvedUrl = rootUrl.resolve(href) ?: href.toHttpUrlOrNull()
        val targetPathSegments = resolvedUrl?.pathSegments?.filter(String::isNotEmpty).orEmpty()
        val isUnderRoot =
            resolvedUrl != null &&
                hasSameOrigin(resolvedUrl, rootUrl) &&
                targetPathSegments.size >= rootPathSegments.size &&
                targetPathSegments.take(rootPathSegments.size) == rootPathSegments
        return if (isUnderRoot) {
            targetPathSegments.drop(rootPathSegments.size).joinToString("/").takeIf(String::isNotEmpty)
        } else {
            null
        }
    }
}

private sealed interface PropfindResult {
    data object Missing : PropfindResult

    data class Present(val body: ByteArray) : PropfindResult
}

private class WebDavHttpException(
    method: String,
    target: String,
    val statusCode: Int,
) : IOException("$method failed for $target: HTTP $statusCode")

private fun okhttp3.Response.requireSuccessful(
    method: String,
    target: String,
) {
    if (!isSuccessful) {
        throw WebDavHttpException(method, target, code)
    }
}

private fun Element.successfulProperties(): Element? {
    val propStats = getElementsByTagNameNS(OkHttpWebDavXml.DAV_NAMESPACE, OkHttpWebDavXml.ELEMENT_PROPSTAT)
    for (index in 0 until propStats.length) {
        val propStat = propStats.item(index) as? Element ?: continue
        val status = propStat.firstDavElement(OkHttpWebDavXml.ELEMENT_STATUS)?.textContent.orEmpty()
        val statusCode = HTTP_STATUS_CODE.find(status)?.groupValues?.get(1)?.toIntOrNull()
        if (statusCode != null && statusCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            return propStat.firstDavElement(OkHttpWebDavXml.ELEMENT_PROP)
        }
    }
    return null
}

private fun Element.firstDavElement(localName: String): Element? {
    val elements = getElementsByTagNameNS(OkHttpWebDavXml.DAV_NAMESPACE, localName)
    return (0 until elements.length)
        .asSequence()
        .mapNotNull { index -> elements.item(index) as? Element }
        .firstOrNull()
}

private fun Element.hasDavDescendant(localName: String): Boolean =
    getElementsByTagNameNS(OkHttpWebDavXml.DAV_NAMESPACE, localName).length > 0

private object OkHttpWebDavXml {
    const val DAV_NAMESPACE = "DAV:"
    const val ELEMENT_COLLECTION = "collection"
    const val ELEMENT_GET_CONTENT_LENGTH = "getcontentlength"
    const val ELEMENT_GET_ETAG = "getetag"
    const val ELEMENT_GET_LAST_MODIFIED = "getlastmodified"
    const val ELEMENT_HREF = "href"
    const val ELEMENT_PROP = "prop"
    const val ELEMENT_PROPSTAT = "propstat"
    const val ELEMENT_RESPONSE = "response"
    const val ELEMENT_STATUS = "status"
}

private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

private fun java.io.InputStream.readBoundedBytes(
    maxBytes: Long,
    path: String,
): ByteArray {
    val maxSize = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val output = ByteArrayOutputStream(minOf(maxSize, DEFAULT_SMALL_FILE_BUFFER_BYTES))
    val buffer = ByteArray(DEFAULT_SMALL_FILE_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxSize) {
            throw IOException("WebDAV file exceeds small-file limit: path=$path bytes=$total max=$maxBytes")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private data class WebDavClientKey(
    val endpointUrl: String,
    val username: String,
    val password: String,
)

private fun parseHttpDate(value: String?): Long? =
    value?.let { httpDate ->
        // behavior-contract: silent-result-ok: non-RFC-1123 dates common on non-compliant servers; null skips timestamp
        runCatching {
            ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

private fun hasSameOrigin(
    targetUrl: HttpUrl,
    rootUrl: HttpUrl,
): Boolean = targetUrl.scheme == rootUrl.scheme && targetUrl.host == rootUrl.host && targetUrl.port == rootUrl.port

private val HTTP_STATUS_CODE = Regex("HTTP/\\S+\\s+(\\d{3})")
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val DEFAULT_SMALL_FILE_BUFFER_BYTES = 8 * 1024
