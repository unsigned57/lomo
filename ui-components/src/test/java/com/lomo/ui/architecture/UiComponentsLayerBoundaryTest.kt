/*
 * Test Contract:
 * - Unit under test: UiComponentsLayerBoundaryTest
 * - Owning layer: ui
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for UiComponentsLayerBoundaryTest.
 * - Boundary: boundary and edge cases for UiComponentsLayerBoundaryTest.
 * - Failure: failure and error scenarios for UiComponentsLayerBoundaryTest.
 * - Must-not-happen: invariants are never violated for UiComponentsLayerBoundaryTest.
 *
 * - Behavior focus: test behavioral outcomes of UiComponentsLayerBoundaryTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.ui.architecture

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class UiComponentsLayerBoundaryTest : UiComponentsFunSpec() {
    private val moduleRoot = resolveModuleRoot("ui-components")
    private val sourceRoot = moduleRoot.resolve("src/main/java")
    private val gradleFile = moduleRoot.resolve("build.gradle.kts")

    init {
        test("ui-components source does not reference data layer package") {
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        withClue("No Kotlin sources found under ui-components/src/main/java") { (kotlinFiles.isNotEmpty()) shouldBe true }

        val offenders = kotlinFiles.filter(::containsDataLayerReference)
        withClue("ui-components module must not reference data layer package. Offenders: ${offenders.joinToString { it.path }}") { (offenders.isEmpty()) shouldBe true }
        }
    }

    init {
        test("ui-components module does not apply hilt or ksp plugins") {
        val text = gradleFile.readText()

        (text.contains("libs.plugins.hilt")) shouldBe false
        (text.contains("libs.plugins.ksp")) shouldBe false
        (text.contains("hilt.android")) shouldBe false
        (text.contains("hilt.compiler")) shouldBe false
        }
    }

    init {
        test("ui-components does not keep app ui state shims") {
        val offenders =
            listOf(
                moduleRoot.resolve("src/main/java/com/lomo/ui/util/UiState.kt"),
                moduleRoot.resolve("src/main/java/com/lomo/ui/util/ViewModelExtensions.kt"),
            ).filter(File::exists)

        withClue("ui-components must not keep ViewModel/UI-state compatibility shims. Offenders: ${offenders.joinToString { it.path }}") { (offenders.isEmpty()) shouldBe true }
        }
    }

    private fun containsDataLayerReference(file: File): Boolean {
        val content = file.readText()
        if (DATA_IMPORT_PATTERN.containsMatchIn(content)) return true
        val nonImportOrPackageContent = stripImportAndPackageLines(content)
        return DATA_FQCN_PATTERN.containsMatchIn(nonImportOrPackageContent)
    }

    private fun stripImportAndPackageLines(content: String): String =
        content
            .lineSequence()
            .filterNot { IMPORT_OR_PACKAGE_LINE_PATTERN.containsMatchIn(it) }
            .joinToString(separator = "\n")

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

    private companion object {
        val DATA_IMPORT_PATTERN = Regex("""(?m)^\s*import\s+com\.lomo\.data(?:\.|$)""")
        val DATA_FQCN_PATTERN = Regex("""\bcom\.lomo\.data\.[A-Za-z_]\w*""")
        val IMPORT_OR_PACKAGE_LINE_PATTERN = Regex("""^\s*(import|package)\s+""")
    }
}
