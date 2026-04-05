package com.lomo.app.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: background theme-sync wiring across MainActivity and LomoApplication.
 * - Behavior focus: when following the system theme, handled uiMode changes must trigger theme synchronization while backgrounded, and the activity-side sync effect must react to both the saved theme mode and the current uiMode.
 * - Observable outcomes: source-level wiring for the MainActivity LaunchedEffect keys and the application configuration-change sync call.
 * - Red phase: Fails before the fix because MainActivity only re-syncs when the saved theme preference changes, so background uiMode flips are not applied until the app returns to the foreground.
 * - Excludes: AppCompatDelegate platform side effects, OEM renderer behavior, and Compose recomposition timing details.
 */
/*
 * Test Change Justification:
 * - Reason category: pure refactor preserved behavior but required mechanical test reshaping.
 * - Exact behavior/assertion being replaced: the contract previously required applyAppNightMode/new-theme callbacks to forward the raw uiMode argument.
 * - Why the previous assertion is no longer correct: follow-system behavior now depends on re-running theme synchronization when uiMode changes, not on threading uiMode through an unused helper parameter.
 * - Retained/new coverage: the test still locks the dual-key LaunchedEffect and the application-side re-apply on handled configuration changes.
 * - Why this is not changing the test to fit the implementation: the observable product contract remains background synchronization; only the internal helper signature was simplified.
 */
class ThemeBackgroundSyncContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val mainActivitySource =
        appModuleRoot.resolve("src/main/java/com/lomo/app/MainActivity.kt")
    private val applicationSource =
        appModuleRoot.resolve("src/main/java/com/lomo/app/LomoApplication.kt")

    @Test
    fun `main activity re-syncs theme when either preference or uiMode changes`() {
        val content = mainActivitySource.readText().normalizeWhitespace()

        assertTrue(
            """
            MainActivity must re-run theme synchronization when the effective uiMode changes under ThemeMode.SYSTEM.
            Expected a LaunchedEffect keyed by both appPreferences.themeMode and currentUiMode, and a sync call
            that re-applies the current theme mode in:
            ${mainActivitySource.path}
            """.trimIndent(),
            content.contains("LaunchedEffect(appPreferences.themeMode, currentUiMode)") &&
                content.contains("onThemeModeChanged(appPreferences.themeMode)"),
        )
    }

    @Test
    fun `application re-syncs app night mode during handled background uiMode changes`() {
        val content = applicationSource.readText().normalizeWhitespace()

        assertTrue(
            """
            LomoApplication must re-apply app night mode during handled configuration changes.
            Expected an explicit configuration-change sync call in:
            ${applicationSource.path}
            """.trimIndent(),
            content.contains("applyAppNightMode(this, currentThemeMode)"),
        )
    }

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
