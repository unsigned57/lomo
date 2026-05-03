package com.lomo.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: markdown delegate directory routing in the data/source layer.
 * - Behavior focus: MAIN/TRASH dispatch must be centralized in a shared helper instead of being
 *   repeated as per-method `when (directory)` branches in each backend delegate.
 * - Observable outcomes: delegate source files stop containing `when (directory)` and instead call
 *   the shared routing helper.
 * - Red phase: Fails before the fix because both direct and SAF markdown delegates inline the same
 *   MAIN/TRASH switch in every method.
 * - Excludes: markdown file contents, SAF traversal, and direct filesystem I/O semantics.
 */
class MarkdownDirectoryRoutingContractTest {
    private val dataModuleRoot = resolveModuleRoot("data")
    private val sourceRoot = dataModuleRoot.resolve("src/main/java/com/lomo/data/source")

    @Test
    fun `markdown delegates use shared directory routing helper instead of repeated when branches`() {
        val directDelegate = sourceRoot.resolve("DirectMarkdownStorageBackendDelegate.kt").readText()
        val safDelegate = sourceRoot.resolve("SafMarkdownStorageBackendDelegate.kt").readText()

        assertTrue(
            "Direct markdown delegate should route through shared helper.",
            directDelegate.contains("routeMarkdownDirectory("),
        )
        assertTrue(
            "SAF markdown delegate should route through shared helper.",
            safDelegate.contains("routeMarkdownDirectory("),
        )
        assertFalse(
            "Direct markdown delegate should no longer inline repeated when(directory) branches.",
            directDelegate.contains("when (directory)"),
        )
        assertFalse(
            "SAF markdown delegate should no longer inline repeated when(directory) branches.",
            safDelegate.contains("when (directory)"),
        )
    }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val parent = currentDir.parentFile ?: currentDir
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                parent.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) { "Failed to resolve $moduleName module root from $currentDirPath" }
    }
}
