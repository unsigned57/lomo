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
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: ForbiddenTestStackImportsRule
 * - Owning layer: quality
 * - Priority tier: P2
 *
 * Scenarios:
 * - Given the happy path, when the rule runs, then import io.kotest.matchers.shouldBe produces no finding.
 * - Given the boundary path, when the rule runs, then production source with banned import produces no finding (rule is test-only).
 * - Given the failure path, when the rule runs, then import org.junit.Assert.assertTrue / org.mockito.Mockito / strikt.api.expectThat each produces a finding.
 * - Given the must-not-happen risk, when tests run, then never flag dev.detekt or kotlinx.coroutines imports.
 *
 * Observable outcomes:
 * - detekt finding count and message mentioning the banned package.
 *
 * TDD proof:
 * - Fails before the rule exists because banned imports return no finding.
 *
 * Excludes:
 * - production source.
 */
class ForbiddenTestStackImportsRuleTest : FunSpec({
    test("flags org.junit.Assert imports") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import org.junit.Assert.assertTrue

            fun example() = assertTrue(true)
            """,
        )
        findings.shouldHaveSize(1)
        findings.single().message shouldContain "org.junit"
    }

    test("flags mockito imports") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import org.mockito.Mockito

            fun example() = Mockito.mock(String::class.java)
            """,
        )
        findings.shouldHaveSize(1)
    }

    test("flags strikt imports") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import strikt.api.expectThat

            fun example() = expectThat(1)
            """,
        )
        findings.shouldHaveSize(1)
    }

    test("flags assertk and assertj imports") {
        val findingsAssertk = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import assertk.assertThat

            fun example() = assertThat(1)
            """,
        )
        val findingsAssertj = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import org.assertj.core.api.Assertions

            fun example() = Assertions.assertThat(1)
            """,
        )
        findingsAssertk.shouldHaveSize(1)
        findingsAssertj.shouldHaveSize(1)
    }

    test("allows kotest and detekt and coroutines imports") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import io.kotest.core.spec.style.FunSpec
            import io.kotest.matchers.shouldBe
            import kotlinx.coroutines.test.runTest

            class S : FunSpec({
                test("ok") {
                    runTest { 1 shouldBe 1 }
                }
            })
            """,
        )
        findings.shouldHaveSize(0)
    }

    test("does not flag banned imports in production source") {
        val findings = rule().findingsForProductionSource(
            """
            package com.lomo.sample

            import org.junit.Assert.assertTrue

            fun example() = assertTrue(true)
            """,
        )
        findings.shouldHaveSize(0)
    }
})

private fun rule(config: Config = Config.empty): Rule =
    checkNotNull(LomoTestStyleRuleSetProvider().instance().rules[RuleName("ForbiddenTestStackImports")]) {
        "Expected rule 'ForbiddenTestStackImports' to be registered."
    }.invoke(config)

private fun Rule.findingsForTestSource(code: String): List<dev.detekt.api.Finding> =
    findingsAt("test/sample/FixtureTest.kt", code)

private fun Rule.findingsForProductionSource(code: String): List<dev.detekt.api.Finding> =
    findingsAt("src/sample/Fixture.kt", code)

private fun Rule.findingsAt(relativePath: String, code: String): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
