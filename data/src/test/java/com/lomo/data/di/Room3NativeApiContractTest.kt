package com.lomo.data.di

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: data module build configuration
 * - Behavior focus: Ensure the data module does not pull in room3-sqlite-wrapper.
 * - Observable outcomes: build.gradle.kts content assertions.
 * - Red phase: Verified by temporarily adding the dependency and watching assertion fail.
 * - Excludes: none.
 */
class Room3NativeApiContractTest {
    private val dataModuleRoot = resolveModuleRoot("data")
    private val buildFile =
        dataModuleRoot.resolve("build.gradle.kts").also { file ->
            check(file.exists()) { "Could not find data/build.gradle.kts at ${file.path}" }
        }

    @Test
    fun `data module does not depend on room3 sqlite wrapper`() {
        val content = buildFile.readText()

        assertFalse(
            "Room3 migration should not depend on room3-sqlite-wrapper when native APIs are required.",
            content.contains("room3.sqlite.wrapper"),
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
