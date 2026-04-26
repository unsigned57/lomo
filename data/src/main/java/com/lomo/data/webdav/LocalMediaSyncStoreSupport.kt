package com.lomo.data.webdav

import com.lomo.data.local.datastore.LomoDataStore
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

internal suspend fun resolveConfiguredMediaRoots(
    cachedRoots: List<MediaRoot>?,
    dataStore: LomoDataStore,
): List<MediaRoot> {
    cachedRoots?.let { roots ->
        return roots
    }
    return buildConfiguredRoots(
        imageDirectory = dataStore.imageDirectory.first(),
        imageUri = dataStore.imageUri.first(),
        voiceDirectory = dataStore.voiceDirectory.first(),
        voiceUri = dataStore.voiceUri.first(),
    )
}

internal fun InputStream.md5Hex(): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
