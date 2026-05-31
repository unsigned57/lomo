package com.lomo.data.testing.fakes

import com.lomo.data.repository.S3SafTreeAccess
import com.lomo.data.repository.S3SafTreeFile
import java.io.File
import java.io.IOException

class FakeS3SafTreeAccess : S3SafTreeAccess {
    private val files = mutableMapOf<String, Pair<S3SafTreeFile, ByteArray>>()

    fun addFile(relativePath: String, bytes: ByteArray, lastModified: Long = System.currentTimeMillis()) {
        val file = S3SafTreeFile(
            relativePath = relativePath,
            lastModified = lastModified,
            size = bytes.size.toLong(),
            documentUri = "content://saf/$relativePath",
            documentId = "saf:$relativePath"
        )
        files[relativePath] = file to bytes
    }

    override suspend fun listFiles(rootUriString: String): List<S3SafTreeFile> {
        return files.values.map { it.first }
    }

    override suspend fun getFile(rootUriString: String, relativePath: String): S3SafTreeFile? {
        return files[relativePath]?.first
    }

    override suspend fun readBytes(rootUriString: String, relativePath: String): ByteArray? {
        return files[relativePath]?.second
    }

    override suspend fun readText(rootUriString: String, relativePath: String): String? {
        return readBytes(rootUriString, relativePath)?.toString(Charsets.UTF_8)
    }

    override suspend fun exportToFile(
        rootUriString: String,
        relativePath: String,
        destination: File
    ): Boolean {
        val bytes = readBytes(rootUriString, relativePath) ?: return false
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        return true
    }

    override suspend fun writeBytes(rootUriString: String, relativePath: String, bytes: ByteArray) {
        addFile(relativePath, bytes)
    }

    override suspend fun importFromFile(rootUriString: String, relativePath: String, source: File) {
        if (!source.exists()) throw IOException("Source file does not exist")
        writeBytes(rootUriString, relativePath, source.readBytes())
    }

    override suspend fun deleteFile(rootUriString: String, relativePath: String) {
        files.remove(relativePath)
    }
}
