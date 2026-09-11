/*
 * Behavior Contract:
 * - Unit under test: AppP0RepositoryBoundaryTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for AppP0RepositoryBoundaryTest.
 * - Boundary: boundary and edge cases for AppP0RepositoryBoundaryTest.
 * - Failure: failure and error scenarios for AppP0RepositoryBoundaryTest.
 * - Must-not-happen: invariants are never violated for AppP0RepositoryBoundaryTest.
 *
 * - Behavior focus: test behavioral outcomes of AppP0RepositoryBoundaryTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.architecture

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class AppP0RepositoryBoundaryTest : AppFunSpec() {
    init {
        test("p0 hotspot files do not import domain repositories") {
            val moduleRoot = resolveModuleRoot("app")
            val offenders =
                HOTSPOT_FILES.filter { relativePath ->
                    val file = moduleRoot.resolve(relativePath)
                    file.readText().lineSequence().any { line ->
                        line.trimStart().startsWith("import com.lomo.domain.repository.")
                    }
                }

            withClue("P0 hotspot files must not import domain repositories. Offenders: ${offenders.joinToString()}") { ((offenders.isEmpty())) shouldBe true }
        }
    }

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
                dir.name == moduleName && dir.resolve("module.yaml").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }

    private companion object {
        val HOTSPOT_FILES =
            listOf(
                "src/feature/settings/SettingsGitCoordinator.kt",
                "src/feature/settings/SettingsWebDavCoordinator.kt",
                "src/feature/main/MainStartupCoordinator.kt",
                "src/feature/main/MainVersionHistoryCoordinator.kt",
                "src/feature/memo/MemoEditorViewModel.kt",
            )
    }
}
