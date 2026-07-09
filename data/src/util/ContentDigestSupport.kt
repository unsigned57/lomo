package com.lomo.data.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal fun InputStream.md5Hex(): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(STREAM_DIGEST_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun File.md5Hex(): String =
    inputStream().use { input -> input.md5Hex() }

private const val STREAM_DIGEST_BUFFER_SIZE = 16 * 1024
