/*
 * Test Contract:
 * - Unit under test: SharedMemoCacheCleanerTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for SharedMemoCacheCleanerTest.
 * - Boundary: boundary and edge cases for SharedMemoCacheCleanerTest.
 * - Failure: failure and error scenarios for SharedMemoCacheCleanerTest.
 * - Must-not-happen: invariants are never violated for SharedMemoCacheCleanerTest.
 *
 * - Behavior focus: test behavioral outcomes of SharedMemoCacheCleanerTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.util

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class SharedMemoCacheCleanerTest : AppFunSpec() {
    init {
        test("cleanup removes stale files and keeps newest within maxFiles") {
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

                val remainingNames =
                    tempDir
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile }
                        .map(File::getName)
                ((remainingNames.contains(stale.name))) shouldBe false
                (remainingNames.size) shouldBe (2)
                ((remainingNames.contains(keepNewest.name))) shouldBe true
                ((remainingNames.contains(keepSecond.name))) shouldBe true
            } finally {
                tempDir.deleteRecursively()
            }
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
