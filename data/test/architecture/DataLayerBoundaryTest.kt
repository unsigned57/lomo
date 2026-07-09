/*
 * Behavior Contract:
 * - Unit under test: DataLayerBoundaryTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for DataLayerBoundaryTest.
 * - Boundary: boundary and edge cases for DataLayerBoundaryTest.
 * - Failure: failure and error scenarios for DataLayerBoundaryTest.
 * - Must-not-happen: invariants are never violated for DataLayerBoundaryTest.
 *
 * - Behavior focus: test behavioral outcomes of DataLayerBoundaryTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.data.architecture

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



import java.io.File
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue

class DataLayerBoundaryTest : DataFunSpec() {
    init {
        test("data layer does not reference app layer package") { `data layer does not reference app layer package`() }

        test("data layer does not reference ui frameworks") { `data layer does not reference ui frameworks`() }
    }


    private val moduleRoot = resolveModuleRoot("data")
    private val sourceRoot = moduleRoot.resolve("src")

    private fun `data layer does not reference app layer package`() {
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        withClue("No Kotlin sources found under data/src") { (kotlinFiles.isNotEmpty()).shouldBeTrue() }

        val offenders = kotlinFiles.filter(::containsAppLayerReference)

        withClue("Data layer must not reference app layer package. Offenders: ${offenders.joinToString { it.path }}") { (offenders.isEmpty()).shouldBeTrue() }
    }

    private fun `data layer does not reference ui frameworks`() {
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        withClue("No Kotlin sources found under data/src") { (kotlinFiles.isNotEmpty()).shouldBeTrue() }

        val offenders = kotlinFiles.filter(::containsUiLayerReference)

        withClue("Data layer must not reference UI packages. Offenders: ${offenders.joinToString { it.path }}") { (offenders.isEmpty()).shouldBeTrue() }
    }

    private fun containsAppLayerReference(file: File): Boolean {
        val content = file.readText()
        if (APP_IMPORT_PATTERN.containsMatchIn(content)) return true
        val nonImportOrPackageContent = stripImportAndPackageLines(content)
        return APP_FQCN_PATTERN.containsMatchIn(nonImportOrPackageContent)
    }

    private fun containsUiLayerReference(file: File): Boolean {
        val content = file.readText()
        if (UI_IMPORT_PATTERN.containsMatchIn(content)) return true
        val nonImportOrPackageContent = stripImportAndPackageLines(content)
        return UI_FQCN_PATTERN.containsMatchIn(nonImportOrPackageContent)
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
                dir.name == moduleName && dir.resolve("module.yaml").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }

    private companion object {
        val APP_IMPORT_PATTERN = Regex("""(?m)^\s*import\s+com\.lomo\.app(?:\.|$)""")
        val APP_FQCN_PATTERN = Regex("""\bcom\.lomo\.app\.[A-Za-z_]\w*""")
        val UI_IMPORT_PATTERN =
            Regex("""(?m)^\s*import\s+(?:com\.lomo\.ui(?:\.|$)|androidx\.compose(?:\.|$)|androidx\.lifecycle\.ViewModel\b)""")
        val UI_FQCN_PATTERN =
            Regex("""\b(?:com\.lomo\.ui\.[A-Za-z_]\w*|androidx\.compose\.[A-Za-z_]\w*|androidx\.lifecycle\.ViewModel\b)""")
        val IMPORT_OR_PACKAGE_LINE_PATTERN = Regex("""^\s*(import|package)\s+""")
    }
}
