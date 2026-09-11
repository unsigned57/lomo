/*
 * Behavior Contract:
 * - Unit under test: DomainPurityTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for DomainPurityTest.
 * - Boundary: boundary and edge cases for DomainPurityTest.
 * - Failure: failure and error scenarios for DomainPurityTest.
 * - Must-not-happen: invariants are never violated for DomainPurityTest.
 *
 * - Behavior focus: test behavioral outcomes of DomainPurityTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.architecture

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


import io.kotest.assertions.withClue
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class DomainPurityTest : DomainFunSpec() {
    private val moduleRoot = resolveModuleRoot("domain")
    init {
        test("domain does not depend on inject annotations") {
            val sourceRoot = moduleRoot.resolve("src")
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val offenders =
                kotlinFiles.filter { file ->
                    val text = file.readText()
                    text.contains("@Inject") || text.contains("javax.inject.Inject")
                }

            withClue("Domain layer must stay framework-agnostic. Offenders: ${offenders.joinToString { it.path }}") { (offenders.isEmpty()) shouldBe true }
        }

        test("domain source only uses model repository and usecase categories") {
            val sourceRoot = moduleRoot.resolve("src")
            val allowedTopLevel = setOf("model", "repository", "usecase")
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val offenders =
                kotlinFiles.filter { file ->
                    val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                    val topLevel = relative.substringBefore('/')
                    topLevel !in allowedTopLevel
                }

            withClue("Domain source categories must be model/repository/usecase. Offenders: " +
                    offenders.joinToString { it.path }) { (offenders.isEmpty()) shouldBe true }
        }

        test("domain repository package only declares interfaces") {
            val sourceRoot = moduleRoot.resolve("src/repository")
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val offenders =
                kotlinFiles.filter { file ->
                    val declarations =
                        file
                            .readLines()
                            .filter { line -> line.trimStart().matches(TOP_LEVEL_DECLARATION_PATTERN) }
                    declarations.any { line -> !line.contains("interface ") }
                }

            withClue("Domain repository contracts must be interfaces only. Offenders: " +
                    offenders.joinToString { it.path }) { (offenders.isEmpty()) shouldBe true }
        }

        test("domain source does not keep compatibility typealias") {
            val sourceRoot = moduleRoot.resolve("src")
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val offenders =
                kotlinFiles.filter { file ->
                    file
                        .readLines()
                        .any { line -> line.trimStart().startsWith("typealias ") }
                }

            withClue("Domain source must not declare typealias compatibility shims. Offenders: " +
                    offenders.joinToString { it.path }) { (offenders.isEmpty()) shouldBe true }
        }

        test("domain module does not use Android build plugin") {
            val buildFile = moduleRoot.resolve("module.yaml")
            val text = buildFile.readText()

            withClue("Domain module must not apply Android build plugins.") { (!text.contains("androidLibrary") && !text.contains("com.android.library")) shouldBe true }
        }

        test("domain module does not keep android manifest") {
            val manifestFile = moduleRoot.resolve("src/AndroidManifest.xml")

            withClue("Domain module must not keep AndroidManifest.xml.") { (!manifestFile.exists()) shouldBe true }
        }

        test("domain module does not depend on inject library") {
            val buildFile = moduleRoot.resolve("module.yaml")
            val text = buildFile.readText()

            withClue("Domain module must not depend on inject libraries.") { (!text.contains("javax.inject") && !text.contains("jakarta.inject") && !text.contains("libs.javax.inject")) shouldBe true }
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
        val TOP_LEVEL_DECLARATION_PATTERN =
            Regex(
                """^(?:public\s+|internal\s+|private\s+)?(?:sealed\s+)?(?:data\s+)?(?:class|object|interface|enum\s+class)\b.*""",
            )
    }
}
