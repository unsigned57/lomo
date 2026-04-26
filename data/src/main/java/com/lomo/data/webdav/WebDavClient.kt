package com.lomo.data.webdav

import java.io.File

interface WebDavClient {
    fun ensureDirectory(path: String)

    fun list(path: String): List<WebDavRemoteResource>

    fun get(path: String): WebDavRemoteFile

    fun getToFile(
        path: String,
        destination: File,
    ) {
        destination.writeBytes(get(path).bytes)
    }

    fun put(
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
    ) {
        put(
            path = path,
            bytes = file.readBytes(),
            contentType = contentType,
            lastModifiedHint = lastModifiedHint,
            expectedEtag = expectedEtag,
            requireAbsent = requireAbsent,
        )
    }

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
