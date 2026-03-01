package com.lomo.app.util

import java.io.File

object SharedMemoCacheCleaner {
    fun cleanup(
        directory: File,
        maxFiles: Int,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!directory.exists() || !directory.isDirectory) return

        val files = directory.listFiles().orEmpty().filter { it.isFile }
        if (files.isEmpty()) return

        files
            .asSequence()
            .filter { nowMs - it.lastModified() > maxAgeMs }
            .forEach { it.delete() }

        val retained =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }

        retained
            .drop(maxFiles.coerceAtLeast(0))
            .forEach { it.delete() }
    }
}
