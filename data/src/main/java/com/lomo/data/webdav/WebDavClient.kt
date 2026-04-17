package com.lomo.data.webdav

interface WebDavClient {
    fun ensureDirectory(path: String)

    fun list(path: String): List<WebDavRemoteResource>

    fun get(path: String): WebDavRemoteFile

    fun put(
        path: String,
        bytes: ByteArray,
        contentType: String,
        lastModifiedHint: Long? = null,
        expectedEtag: String? = null,
        requireAbsent: Boolean = false,
    )

    fun delete(
        path: String,
        expectedEtag: String? = null,
    )

    fun testConnection()
}

data class WebDavRemoteResource(
    val path: String,
    val isDirectory: Boolean,
    val etag: String?,
    val lastModified: Long?,
)

data class WebDavRemoteFile(
    val path: String,
    val bytes: ByteArray,
    val etag: String?,
    val lastModified: Long?,
)

fun interface WebDavClientFactory {
    fun create(
        endpointUrl: String,
        username: String,
        password: String,
    ): WebDavClient
}
