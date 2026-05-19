package com.lomo.detektrules

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


import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: NoPerTestInitBlockRule
 * - Owning layer: quality
 * - Priority tier: P2
 *
 * Scenarios:
 * - Given the happy path, when the rule runs, then single init { } with multiple test("...") calls produces no finding.
 * - Given the boundary path, when the rule runs, then constructor-block FunSpec({ ... }) form produces no finding.
 * - Given the failure path, when the rule runs, then multiple init blocks each containing one test("...") produces one finding per offending class.
 * - Given the must-not-happen risk, when tests run, then rule must not flag non-test classes with multiple init blocks.
 *
 * Observable outcomes:
 * - detekt finding count and message text mention the per-test init anti-pattern.
 *
 * TDD proof:
 * - Fails before the rule exists because the per-test init fixture returns no finding.
 *
 * Excludes:
 * - production-source classes with init blocks, BehaviorSpec given/when blocks (handled by other rules).
 */
class NoPerTestInitBlockRuleTest : FunSpec({
    test("registers NoPerTestInitBlock rule") {
        val rules = LomoTestStyleRuleSetProvider().instance().rules
        rules[RuleName("NoPerTestInitBlock")].shouldNotBeNull()
    }

    test("flags multiple init blocks each holding one test call") {
        val findings =
            rule("NoPerTestInitBlock").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.core.spec.style.FunSpec

                class XTest : FunSpec() {
                    init { test("a") { } }
                    init { test("b") { } }
                    init { test("c") { } }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "per-test init"
    }

    test("allows single init block with multiple test calls") {
        val findings =
            rule("NoPerTestInitBlock").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.core.spec.style.FunSpec

                class XTest : FunSpec() {
                    init {
                        test("a") { }
                        test("b") { }
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("allows constructor-block FunSpec form") {
        val findings =
            rule("NoPerTestInitBlock").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.core.spec.style.FunSpec

                class XTest : FunSpec({
                    test("a") { }
                    test("b") { }
                })
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("does not flag non-test class with multiple init blocks") {
        val findings =
            rule("NoPerTestInitBlock").findingsForTestSource(
                """
                package com.lomo.sample

                class Helper {
                    init { println("one") }
                    init { println("two") }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }
})

private fun rule(
    name: String,
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoTestStyleRuleSetProvider().instance().rules[RuleName(name)]) {
        "Expected rule '$name' to be registered."
    }.invoke(config)

private fun Rule.findingsForTestSource(code: String): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve("src/test/java/com/lomo/sample/FixtureTest.kt")
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
