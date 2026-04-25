package com.lomo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MainActivity (source-level contract)
 * - Behavior focus: MainActivity must use only a biometric app-lock gate (appLockEnabled) and
 *   must NOT reference DatabaseStartupRequirement, SQLCipher, or any database-unlock mechanism.
 *   The database is always plaintext; no runtime passphrase or unlock gating is needed.
 * - Observable outcomes: Source-level absence of cipher and database-unlock symbols in
 *   MainActivity.kt and adjacent activity-layer files.
 * - Red phase: Not applicable – MainActivity currently uses plaintext Room with no cipher gate.
 *   These tests will FAIL if cipher or database-unlock code is ever introduced.
 * - Excludes: Runtime Activity behavior, Compose navigation, and biometric prompt logic.
 */
class MainActivityDatabaseGateContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val mainActivityFile =
        appModuleRoot.resolve("src/main/java/com/lomo/app/MainActivity.kt")

    @Test
    fun `MainActivity does not reference DatabaseStartupRequirement`() {
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        assertFalse(
            "MainActivity must not reference DatabaseStartupRequirement",
            mainActivityFile.readText().contains("DatabaseStartupRequirement"),
        )
    }

    @Test
    fun `MainActivity does not import SQLCipher`() {
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val content = mainActivityFile.readText()
        assertFalse(
            "MainActivity must not import SQLCipher",
            content.contains("sqlcipher", ignoreCase = true) || content.contains("net.zetetic"),
        )
    }

    @Test
    fun `MainActivity does not reference database unlock or cipher key provisioning`() {
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val content = mainActivityFile.readText()
        val forbidden =
            listOf("databaseUnlock", "DatabaseUnlock", "unlockDatabase", "cipherKey", "dbPassphrase")
        val violations = forbidden.filter { content.contains(it) }
        assertTrue(
            "MainActivity must not reference database-unlock or cipher-key symbols. Violations: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `app module does not declare a DatabaseStartupRequirement class`() {
        val sourceRoot = appModuleRoot.resolve("src/main/java")
        val offenders =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("class DatabaseStartupRequirement") }
                .toList()

        assertTrue(
            "app module must not declare DatabaseStartupRequirement. Offenders: ${offenders.joinToString { it.name }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `data module does not declare a DatabaseStartupRequirement class`() {
        val dataSourceRoot = resolveModuleRoot("data").resolve("src/main/java")
        val offenders =
            dataSourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("class DatabaseStartupRequirement") }
                .toList()

        assertTrue(
            "data module must not declare DatabaseStartupRequirement. Offenders: ${offenders.joinToString { it.name }}",
            offenders.isEmpty(),
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
