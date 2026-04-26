package com.lomo.app.feature.search

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: SearchScreen top-bar search input wiring
 * - Behavior focus: search screen must use the Material 3 SearchBar implementation instead of the handwritten BasicTextField search box.
 * - Observable outcomes: source contracts show SearchScreen imports and renders DockedSearchBar/SearchBar APIs and no longer declares a custom BasicTextField-based SearchQueryField.
 * - Red phase: Fails before the fix because SearchScreen still renders a hand-written BasicTextField search box.
 * - Excludes: Compose runtime measurement, focus timing, and result-list rendering.
 */
class SearchScreenContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val searchScreenSource =
        appModuleRoot.resolve("src/main/java/com/lomo/app/feature/search/SearchScreen.kt")

    @Test
    fun `search screen uses material docked search bar instead of handwritten text field`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen must use Material 3 SearchBar/DockedSearchBar APIs so the search input follows the
            repository's Material 3 Expressive migration instead of maintaining a hand-written BasicTextField.
            """.trimIndent(),
            content.contains("DockedSearchBar(") &&
                !content.contains("BasicTextField(") &&
                !content.contains("private fun SearchQueryField("),
        )
    }

    @Test
    fun `search screen top bar applies status bar padding to avoid overlap`() {
        val content = searchScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SearchScreen must apply status-bar inset padding around the SearchBar container.
            Otherwise the docked search bar overlaps the system top bar after the M3 migration.
            """.trimIndent(),
            content.contains("statusBarsPadding()"),
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
