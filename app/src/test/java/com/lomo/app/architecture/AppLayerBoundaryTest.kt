/*
 * Behavior Contract:
 * - Unit under test: AppLayerBoundaryTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for AppLayerBoundaryTest.
 * - Boundary: boundary and edge cases for AppLayerBoundaryTest.
 * - Failure: failure and error scenarios for AppLayerBoundaryTest.
 * - Must-not-happen: invariants are never violated for AppLayerBoundaryTest.
 *
 * - Behavior focus: test behavioral outcomes of AppLayerBoundaryTest.
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

class AppLayerBoundaryTest : AppFunSpec() {
    private val kotlinFileExtension = "kt"
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src/main/java")
    private val gradleFile = moduleRoot.resolve("build.gradle.kts")

    init {
        test("app source does not reference data layer package") {
            val kotlinFiles = collectKotlinFiles()
            withClue("No Kotlin sources found in app/src/main/java") { ((kotlinFiles.isNotEmpty())) shouldBe true }

            val offenders = kotlinFiles.filter(::containsDataLayerReference)
            withClue("App layer must not reference data layer package. Offenders: ${offenders.joinToString { it.path }}") { ((offenders.isEmpty())) shouldBe true }
        }
    }

    init {
        test("app module does not expose data dependency outside implementation") {
            val content = gradleFile.readText()
            val offenders =
                FORBIDDEN_DATA_DEPENDENCY_PATTERNS
                    .filter { it.pattern.containsMatchIn(content) }
                    .map { it.description }

            withClue("app/build.gradle.kts must not expose :data outside implementation. Offenders: ${offenders.joinToString()}") { ((offenders.isNotEmpty())) shouldBe false }
        }
    }

    private fun collectKotlinFiles(): List<File> =
        sourceRoot
            .takeIf(File::exists)
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == kotlinFileExtension }
            ?.toList()
            .orEmpty()

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

        val FORBIDDEN_DATA_DEPENDENCY_PATTERNS =
            listOf(
                ForbiddenDependencyPattern(
                    description = "api(project(\":data\")) / compileOnly(project(\":data\")) / ksp(project(\":data\"))",
                    pattern =
                        Regex(
                            """(?s)\b(?:api|compileOnly|ksp)\s*\(\s*project\s*\(\s*(?:path\s*=\s*)?['"]:data['"][^)]*\)\s*\)""",
                        ),
                ),
                ForbiddenDependencyPattern(
                    description = "api(projects.data) / compileOnly(projects.data) / ksp(projects.data)",
                    pattern =
                        Regex(
                            """(?s)\b(?:api|compileOnly|ksp)\s*\(\s*projects\.data\s*\)""",
                        ),
                ),
                ForbiddenDependencyPattern(
                    description = "add(\"api|compileOnly|ksp\", project(\":data\"))",
                    pattern =
                        Regex(
                            """(?s)\badd\s*\(\s*['"](?:api|compileOnly|ksp)['"]\s*,\s*project\s*\(\s*(?:path\s*=\s*)?['"]:data['"][^)]*\)\s*\)""",
                        ),
                ),
                ForbiddenDependencyPattern(
                    description = "add(\"api|compileOnly|ksp\", projects.data)",
                    pattern =
                        Regex(
                            """(?s)\badd\s*\(\s*['"](?:api|compileOnly|ksp)['"]\s*,\s*projects\.data\s*\)""",
                        ),
                ),
            )
    }

    private data class ForbiddenDependencyPattern(
        val description: String,
        val pattern: Regex,
    )
}
