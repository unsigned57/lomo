package com.lomo.app.navigation

import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class ShareRoutePayloadPersistentCache(
    private val directory: File,
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun put(
        key: String,
        payload: String,
    ) {
        runCatching {
            directory.mkdirs()
            pruneLocked()
            val target = fileForKey(key)
            val temp = File(directory, "${target.name}.tmp")
            temp.writeText(payload, StandardCharsets.UTF_8)
            if (target.exists() && !target.delete()) {
                Timber.w("Unable to replace share route payload cache entry: %s", target.name)
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target = target, overwrite = true)
                temp.delete()
            }
            target.setLastModified(clock())
            pruneLocked()
        }.onFailure { error ->
            Timber.w(error, "Unable to persist share route payload")
        }
    }

    fun consume(key: String): String? =
        runCatching {
            pruneLocked()
            val file = fileForKey(key)
            if (!file.isFile) {
                return@runCatching null
            }
            val payload = file.readText(StandardCharsets.UTF_8)
            if (!deletePayloadFile(file)) {
                return@runCatching null
            }
            payload
        }.onFailure { error ->
            Timber.w(error, "Unable to consume share route payload")
        }.getOrNull()

    fun discard(key: String): Boolean =
        runCatching {
            pruneLocked()
            val file = fileForKey(key)
            !file.isFile || deletePayloadFile(file)
        }.onFailure { error ->
            Timber.w(error, "Unable to discard share route payload")
        }.getOrDefault(false)

    fun clear() {
        runCatching {
            payloadFiles().forEach(File::delete)
        }.onFailure { error ->
            Timber.w(error, "Unable to clear share route payload cache")
        }
    }

    private fun pruneLocked() {
        val now = clock()
        val aliveFiles =
            payloadFiles()
                .filterNot { file ->
                    val expired = now - file.lastModified() > ttlMillis
                    if (expired) {
                        file.delete()
                    }
                    expired
                }.sortedByDescending(File::lastModified)

        aliveFiles
            .drop(maxEntries)
            .forEach(File::delete)
    }

    private fun payloadFiles(): List<File> =
        directory
            .listFiles { file -> file.isFile && file.extension == FILE_EXTENSION }
            ?.toList()
            .orEmpty()

    private fun deletePayloadFile(file: File): Boolean {
        if (file.delete()) {
            return true
        }
        Timber.w("Unable to delete consumed share route payload cache entry: %s", file.name)
        return false
    }

    private fun fileForKey(key: String): File {
        val encodedKey =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(key.toByteArray(StandardCharsets.UTF_8))
        return File(directory, "$encodedKey.$FILE_EXTENSION")
    }

    private companion object {
        const val FILE_EXTENSION = "payload"
    }
}
