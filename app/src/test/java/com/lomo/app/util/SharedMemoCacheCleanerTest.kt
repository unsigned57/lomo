package com.lomo.app.util

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMemoCacheCleanerTest {
    @Test
    fun `cleanup removes stale files and keeps newest within maxFiles`() {
        val tempDir = createTempDirectory("shared-memo-cache-test").toFile()
        try {
            val nowMs = 10_000L
            val stale = createFile(tempDir, "stale.png", nowMs - 5_000L)
            val keepNewest = createFile(tempDir, "newest.png", nowMs - 10L)
            val keepSecond = createFile(tempDir, "second.png", nowMs - 20L)
            createFile(tempDir, "third.png", nowMs - 30L)

            SharedMemoCacheCleaner.cleanup(
                directory = tempDir,
                maxFiles = 2,
                maxAgeMs = 1_000L,
                nowMs = nowMs,
            )

            val remainingNames = tempDir.listFiles().orEmpty().filter { it.isFile }.map(File::getName)
            assertFalse(remainingNames.contains(stale.name))
            assertEquals(2, remainingNames.size)
            assertTrue(remainingNames.contains(keepNewest.name))
            assertTrue(remainingNames.contains(keepSecond.name))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createFile(
        dir: File,
        name: String,
        lastModifiedMs: Long,
    ): File {
        val file = File(dir, name)
        file.writeText(name)
        file.setLastModified(lastModifiedMs)
        return file
    }
}
