package com.lomo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MainActivity startup gate (source-level contract).
 * - Behavior focus: app startup must keep only the biometric/app-lock gate and must not reintroduce
 *   a separate database unlock state, gate ViewModel, or DatabaseStartupRequirement-dependent splash
 *   routing.
 * - Observable outcomes: MainActivity keeps splash visibility tied to MainViewModel loading plus
 *   unresolved app-lock state, wires only `onRequestUnlock`, and does not reference database gate
 *   symbols.
 * - Red phase: Not applicable - test-only coverage lock-in for the current plaintext-database startup
 *   contract.
 * - Excludes: runtime biometric prompts, Compose rendering, and ViewModel instantiation behavior.
 */
class MainActivityGateViewModelTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val mainActivityFile =
        appModuleRoot.resolve(
            "src/main/java/com/lomo/app/MainActivity.kt",
        )

    @Test
    fun `MainActivity keeps splash visibility tied to main loading state and unresolved app lock only`() {
        val content = mainActivityFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Splash visibility must depend on MainViewModel loading and unresolved app-lock state only.
            """.trimIndent(),
            content.contains("private fun shouldKeepSplashScreenVisible(): Boolean =") &&
                content.contains("viewModel.uiState.value is MainViewModel.MainScreenState.Loading") &&
                content.contains("viewModel.appLockEnabled.value == null") &&
                !content.contains("DatabaseStartupRequirement"),
        )
    }

    @Test
    fun `MainActivity startup content wiring exposes only biometric app unlock callback`() {
        val content = mainActivityFile.readText().normalizeWhitespace()

        assertTrue(
            """
            MainActivity must wire only the app unlock callback into MainActivityScreen and must not
            pass a separate database unlock callback.
            """.trimIndent(),
            content.contains("MainActivityScreen(") &&
                content.contains("onRequestUnlock = {") &&
                content.contains("requestAppUnlock(") &&
                !content.contains("onRequestDatabaseUnlock"),
        )
    }

    @Test
    fun `MainActivity uses MainViewModel directly and does not introduce a separate gate view model`() {
        val content = mainActivityFile.readText().normalizeWhitespace()

        assertTrue(
            """
            MainActivity must keep a single MainViewModel entrypoint and avoid a dedicated gate ViewModel
            for database unlock flow.
            """.trimIndent(),
            content.contains("private val viewModel: MainViewModel by viewModels()") &&
                !content.contains("MainActivityGateViewModel"),
        )
    }

    @Test
    fun `MainActivity source does not reference database unlock state or SQLCipher symbols`() {
        val content = mainActivityFile.readText()
        val forbiddenTokens =
            listOf(
                "databaseUnlock",
                "DatabaseUnlock",
                "DatabaseStartupRequirement",
                "unlockDatabase",
                "sqlcipher",
                "net.zetetic",
            )

        val violations =
            forbiddenTokens.filter { token ->
                content.contains(token, ignoreCase = token == "sqlcipher")
            }

        assertTrue(
            "MainActivity must not reference database unlock or SQLCipher symbols. Violations: $violations",
            violations.isEmpty(),
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

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()
}
