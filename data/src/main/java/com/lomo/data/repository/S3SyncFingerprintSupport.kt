package com.lomo.data.repository

import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal fun normalizeSinglePartS3Md5(etag: String?): String? {
    val normalized = etag?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.lowercase(Locale.ROOT)
    return normalized?.takeIf { value ->
        value.length == SINGLE_PART_MD5_HEX_LENGTH &&
            value.all { it in '0'..'9' || it in 'a'..'f' } &&
            '-' !in value
    }
}

internal fun ByteArray.md5Hex(): String =
    MessageDigest
        .getInstance("MD5")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun File.md5Hex(): String =
    inputStream().use { input ->
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(FILE_DIGEST_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

private const val SINGLE_PART_MD5_HEX_LENGTH = 32
private const val FILE_DIGEST_BUFFER_SIZE = 16 * 1024
