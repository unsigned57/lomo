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
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: ShouldBeInstanceOfAssertionRule
 * - Owning layer: quality
 * - Priority tier: P2
 *
 * Scenarios:
 * - Given the happy path, when the rule runs, then `(result is Error) shouldBe true` is reported with a type-narrowing message.
 * - Given the boundary path, when the rule runs, then non-type boolean assertions like `items.isEmpty() shouldBe true` are allowed.
 * - Given the failure path, when the rule runs, then `shouldBe false` is not reported because it is not a type-narrowing replacement.
 * - Given the must-not-happen risk, when tests run, then the rule must not flag ordinary runtime boolean checks.
 *
 * Observable outcomes:
 * - detekt finding count and message text.
 *
 * TDD proof:
 * - Fails before the fix because the rule is not registered and the type-check assertion returns no finding.
 *
 * Excludes:
 * - full Gradle detekt task integration and Kotlin type resolution.
 */
class ShouldBeInstanceOfAssertionRuleTest : FunSpec({
    test("registers the shouldBeInstanceOf assertion guard") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules

        rules[RuleName("ShouldBeInstanceOfAssertion")].shouldNotBeNull()
    }

    test("reports parenthesized type check asserted with shouldBe true") {
        val findings =
            rule("ShouldBeInstanceOfAssertion").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.matchers.shouldBe

                fun assertion(result: Any) {
                    (result is IllegalStateException) shouldBe true
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldBe "Use shouldBeInstanceOf<T>() instead of asserting `(x is T) shouldBe true`."
    }

    test("allows ordinary boolean assertions and negative type checks") {
        val findings =
            rule("ShouldBeInstanceOfAssertion").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.matchers.shouldBe

                fun assertion(items: List<String>, result: Any) {
                    items.isEmpty() shouldBe true
                    (result is IllegalArgumentException) shouldBe false
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
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(name)]) {
        "Expected rule '$name' to be registered."
    }.invoke(config)

private fun Rule.findingsForTestSource(code: String): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve("src/test/java/com/lomo/sample/FixtureTest.kt")
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
