package com.lomo.data.repository

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal suspend fun persistMemoVersionBlobIfNeeded(
    store: MemoVersionStore,
    blobRoot: File,
    bytes: ByteArray,
    contentEncoding: String,
    createdAt: Long,
): String {
    val blobHash = bytes.toVersionHash()
    if (store.getBlob(blobHash) != null) {
        return blobHash
    }
    val blobFile = resolveMemoVersionBlobFile(blobRoot, blobHash)
    val relativeStoragePath = resolveMemoVersionBlobRelativePath(blobHash)
    blobFile.parentFile?.mkdirs()
    if (!blobFile.exists()) {
        blobFile.writeBytes(bytes)
    }
    store.insertBlob(
        MemoVersionBlobRecord(
            blobHash = blobHash,
            storagePath = relativeStoragePath,
            byteSize = blobFile.length(),
            contentEncoding = contentEncoding,
            createdAt = createdAt,
        ),
    )
    return blobHash
}

internal suspend fun readMemoVersionBlobBytes(
    store: MemoVersionStore,
    blobRoot: File,
    blobHash: String,
): ByteArray {
    val blob = requireNotNull(store.getBlob(blobHash)) { "Missing memo version blob metadata for $blobHash" }
    val file = resolveManagedMemoVersionBlobFile(blobRoot, blob.storagePath)
    val bytes = file.readBytes()
    check(bytes.toVersionHash() == blobHash) { "Memo version blob hash mismatch for $blobHash" }
    return bytes
}

internal suspend fun readMemoVersionBlobContent(
    store: MemoVersionStore,
    blobRoot: File,
    blobHash: String,
): String = readMemoVersionBlobBytes(store, blobRoot, blobHash).toString(StandardCharsets.UTF_8)

internal suspend fun deleteMemoVersionBlobIfUnreferenced(
    store: MemoVersionStore,
    blobRoot: File,
    blobHash: String,
) {
    if (store.isBlobReferenced(blobHash)) {
        return
    }
    val blob = store.getBlob(blobHash) ?: return
    val file = resolveManagedMemoVersionBlobFile(blobRoot, blob.storagePath)
    if (file.exists() && !file.delete()) {
        return
    }
    store.deleteBlob(blobHash)
}

internal fun ByteArray.toVersionHash(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun String.toVersionHash(): String = toByteArray(StandardCharsets.UTF_8).toVersionHash()

internal fun resolveMemoVersionBlobFile(
    blobRoot: File,
    blobHash: String,
): File = File(blobRoot, resolveMemoVersionBlobRelativePath(blobHash))

internal fun resolveMemoVersionBlobRelativePath(blobHash: String): String {
    val first = blobHash.take(2)
    val second = blobHash.drop(2).take(2)
    return "$first/$second/$blobHash.blob"
}

internal fun resolveManagedMemoVersionBlobFile(
    blobRoot: File,
    storagePath: String,
): File {
    val file = File(storagePath)
    val candidateFile = if (file.isAbsolute) file else File(blobRoot, storagePath)
    val rootPath = blobRoot.canonicalFile.toPath()
    val candidatePath = candidateFile.canonicalFile.toPath()
    require(candidatePath.startsWith(rootPath)) {
        "Memo version blob path escapes managed root: $storagePath"
    }
    return candidateFile
}
