package com.lomo.data.webdav

import java.io.File

interface WebDavClient {
    fun ensureDirectory(path: String)

    fun list(path: String): List<WebDavRemoteResource>

    fun getToFile(
        path: String,
        destination: File,
    ): WebDavRemoteResource

    /*
     * Behavior Contract:
     * Small-file payload helpers are reserved for memo text, conflict previews, and focused tests.
     * Remote object sync must use getToFile/putFile so large payloads never make ByteArray the
     * primary transport contract.
     */
    fun getSmallFile(path: String): WebDavSmallRemoteFile

    fun getSmallFile(
        path: String,
        maxBytes: Long,
    ): WebDavSmallRemoteFile = getSmallFile(path)

    fun putSmallFile(
        path: String,
        bytes: ByteArray,
        contentType: String,
        lastModifiedHint: Long? = null,
        expectedEtag: String? = null,
        requireAbsent: Boolean = false,
    )

    fun putFile(
        path: String,
        file: File,
        contentType: String,
        lastModifiedHint: Long? = null,
        expectedEtag: String? = null,
        requireAbsent: Boolean = false,
    )

    fun delete(
        path: String,
        expectedEtag: String? = null,
    )

    fun move(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean = true,
    ) {
        throw UnsupportedOperationException("WebDAV MOVE is not supported by this client")
    }

    fun copy(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean = true,
    ) {
        throw UnsupportedOperationException("WebDAV COPY is not supported by this client")
    }

    fun testConnection()
}

data class WebDavRemoteResource(
    val path: String,
    val isDirectory: Boolean,
    val etag: String?,
    val lastModified: Long?,
    val size: Long? = null,
)

data class WebDavSmallRemoteFile(
    val path: String,
    val bytes: ByteArray,
    val etag: String?,
    val lastModified: Long?,
)

const val WEBDAV_SMALL_FILE_MAX_BYTES: Long = 256L * 1024L

fun interface WebDavClientFactory {
    fun create(
        endpointUrl: String,
        username: String,
        password: String,
    ): WebDavClient
}
