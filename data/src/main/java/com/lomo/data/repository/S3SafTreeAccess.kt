package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

data class S3SafTreeFile(
    val relativePath: String,
    val lastModified: Long,
    val size: Long? = null,
)

interface S3SafTreeAccess {
    suspend fun listFiles(rootUriString: String): List<S3SafTreeFile>

    suspend fun getFile(
        rootUriString: String,
        relativePath: String,
    ): S3SafTreeFile?

    suspend fun readBytes(
        rootUriString: String,
        relativePath: String,
    ): ByteArray?

    suspend fun readText(
        rootUriString: String,
        relativePath: String,
    ): String?

    suspend fun exportToFile(
        rootUriString: String,
        relativePath: String,
        destination: File,
    ): Boolean

    suspend fun writeBytes(
        rootUriString: String,
        relativePath: String,
        bytes: ByteArray,
    )

    suspend fun importFromFile(
        rootUriString: String,
        relativePath: String,
        source: File,
    )

    suspend fun deleteFile(
        rootUriString: String,
        relativePath: String,
    )
}

@Singleton
class AndroidS3SafTreeAccess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : S3SafTreeAccess {
        override suspend fun listFiles(rootUriString: String): List<S3SafTreeFile> =
            withContext(Dispatchers.IO) {
                val root = resolveRoot(rootUriString) ?: return@withContext emptyList()
                buildList {
                    appendFiles(
                        directory = root,
                        prefix = "",
                        results = this,
                    )
                }
            }

        override suspend fun getFile(
            rootUriString: String,
            relativePath: String,
        ): S3SafTreeFile? =
            withContext(Dispatchers.IO) {
                val root = resolveRoot(rootUriString) ?: return@withContext null
                val sanitized = sanitizeRelativePath(relativePath) ?: return@withContext null
                val document = findDocument(root, sanitized) ?: return@withContext null
                if (!document.isFile) {
                    return@withContext null
                }
                S3SafTreeFile(
                    relativePath = sanitized,
                    lastModified = document.lastModified(),
                    size = document.length(),
                )
            }

        override suspend fun readBytes(
            rootUriString: String,
            relativePath: String,
        ): ByteArray? =
            withContext(Dispatchers.IO) {
                val root = resolveRoot(rootUriString) ?: return@withContext null
                val sanitized = sanitizeRelativePath(relativePath) ?: return@withContext null
                val document = findDocument(root, sanitized) ?: return@withContext null
                context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
            }

        override suspend fun readText(
            rootUriString: String,
            relativePath: String,
        ): String? = readBytes(rootUriString, relativePath)?.toString(StandardCharsets.UTF_8)

        override suspend fun exportToFile(
            rootUriString: String,
            relativePath: String,
            destination: File,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val root = resolveRoot(rootUriString) ?: return@withContext false
                val sanitized = sanitizeRelativePath(relativePath) ?: return@withContext false
                val document = findDocument(root, sanitized) ?: return@withContext false
                destination.parentFile?.mkdirs()
                context.contentResolver.openInputStream(document.uri)?.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext false
                true
            }

        override suspend fun writeBytes(
            rootUriString: String,
            relativePath: String,
            bytes: ByteArray,
        ) {
            withContext(Dispatchers.IO) {
                val root = requireNotNull(resolveRoot(rootUriString)) { "Cannot access SAF root directory" }
                val sanitized = requireNotNull(sanitizeRelativePath(relativePath)) {
                    "Invalid SAF relative path"
                }
                val parentPath = sanitized.substringBeforeLast('/', "")
                val filename = sanitized.substringAfterLast('/')
                val parent = ensureDirectory(root, parentPath)
                var target = parent.findFile(filename)
                if (target != null && target.isDirectory) {
                    target.delete()
                    target = null
                }
                if (target == null) {
                    target =
                        parent.createFile(contentTypeForRelativePath(sanitized), filename)
                            ?: throw IOException("Cannot create SAF file: $sanitized")
                }
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("Cannot open SAF output stream: $sanitized")
            }
        }

        override suspend fun importFromFile(
            rootUriString: String,
            relativePath: String,
            source: File,
        ) {
            withContext(Dispatchers.IO) {
                val root = requireNotNull(resolveRoot(rootUriString)) { "Cannot access SAF root directory" }
                val sanitized = requireNotNull(sanitizeRelativePath(relativePath)) {
                    "Invalid SAF relative path"
                }
                val parentPath = sanitized.substringBeforeLast('/', "")
                val filename = sanitized.substringAfterLast('/')
                val parent = ensureDirectory(root, parentPath)
                var target = parent.findFile(filename)
                if (target != null && target.isDirectory) {
                    target.delete()
                    target = null
                }
                if (target == null) {
                    target =
                        parent.createFile(contentTypeForRelativePath(sanitized), filename)
                            ?: throw IOException("Cannot create SAF file: $sanitized")
                }
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Cannot open SAF output stream: $sanitized")
            }
        }

        override suspend fun deleteFile(
            rootUriString: String,
            relativePath: String,
        ) {
            withContext(Dispatchers.IO) {
                val root = resolveRoot(rootUriString) ?: return@withContext
                val sanitized = sanitizeRelativePath(relativePath) ?: return@withContext
                findDocument(root, sanitized)?.delete()
            }
        }

        private fun appendFiles(
            directory: DocumentFile,
            prefix: String,
            results: MutableList<S3SafTreeFile>,
        ) {
            directory.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (name.startsWith(".")) {
                    return@forEach
                }
                val relativePath = joinRelativePath(prefix, name)
                when {
                    child.isDirectory -> appendFiles(child, relativePath, results)
                    child.isFile ->
                        results +=
                            S3SafTreeFile(
                                relativePath = relativePath,
                                lastModified = child.lastModified(),
                                size = child.length(),
                            )
                }
            }
        }

        private fun resolveRoot(rootUriString: String): DocumentFile? =
            runCatching { DocumentFile.fromTreeUri(context, rootUriString.toUri()) }
                .getOrNull()
                ?.takeIf { it.exists() && it.isDirectory }

        private fun ensureDirectory(
            root: DocumentFile,
            relativePath: String,
        ): DocumentFile {
            if (relativePath.isBlank()) {
                return root
            }
            var current = root
            relativePath.split('/').filter(String::isNotBlank).forEach { segment ->
                var child = current.findFile(segment)
                if (child != null && !child.isDirectory) {
                    child.delete()
                    child = null
                }
                if (child == null) {
                    child =
                        current.createDirectory(segment)
                            ?: throw IOException("Cannot create SAF directory: $relativePath")
                }
                current = child
            }
            return current
        }

        private fun findDocument(
            root: DocumentFile,
            relativePath: String,
        ): DocumentFile? {
            var current = root
            relativePath.split('/').filter(String::isNotBlank).forEach { segment ->
                current = current.findFile(segment) ?: return null
            }
            return current
        }
    }

internal object UnsupportedS3SafTreeAccess : S3SafTreeAccess {
    override suspend fun listFiles(rootUriString: String): List<S3SafTreeFile> =
        error("S3 SAF tree access is not configured for this test instance")

    override suspend fun getFile(
        rootUriString: String,
        relativePath: String,
    ): S3SafTreeFile? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun readBytes(
        rootUriString: String,
        relativePath: String,
    ): ByteArray? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun readText(
        rootUriString: String,
        relativePath: String,
    ): String? = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun exportToFile(
        rootUriString: String,
        relativePath: String,
        destination: File,
    ): Boolean = error("S3 SAF tree access is not configured for this test instance")

    override suspend fun writeBytes(
        rootUriString: String,
        relativePath: String,
        bytes: ByteArray,
    ) {
        error("S3 SAF tree access is not configured for this test instance")
    }

    override suspend fun importFromFile(
        rootUriString: String,
        relativePath: String,
        source: File,
    ) {
        error("S3 SAF tree access is not configured for this test instance")
    }

    override suspend fun deleteFile(
        rootUriString: String,
        relativePath: String,
    ) {
        error("S3 SAF tree access is not configured for this test instance")
    }
}
