package com.lomo.data.worker

import androidx.work.Data
import java.io.File
import java.io.IOException

interface DeferredLockWorkStore {
    fun save(input: Data)

    fun take(): Data?

    /** Drops any pending deferred work without consuming it (stale-input purge). */
    fun clear()
}

class FileDeferredLockWorkStore(
    private val file: File,
) : DeferredLockWorkStore {
    override fun save(input: Data) {
        val bytes = input.toByteArray()
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        file.writeBytes(bytes)
    }

    override fun take(): Data? {
        if (!file.isFile || file.length() == 0L) {
            return null
        }
        val length = file.length()
        if (length > Data.MAX_DATA_BYTES) {
            file.delete()
            throw IOException("deferred lock work exceeds its byte budget")
        }
        val bytes = ByteArray(length.toInt())
        file.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) {
                    file.delete()
                    throw IOException("deferred lock work was truncated")
                }
                offset += count
            }
        }
        file.delete()
        return Data.fromByteArray(bytes)
    }

    override fun clear() {
        file.delete()
    }
}
