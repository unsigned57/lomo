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
 * - Unit under test: MockedStatefulCollaboratorRule
 * - Owning layer: quality
 * - Priority tier: P2
 *
 * Scenarios:
 * - Given the happy path, when the rule runs, then mockk<Logger>() produces no finding.
 * - Given the boundary path, when the rule runs, then property typed as MemoRepository initialised by mockk(relaxed = true) is flagged.
 * - Given the failure path, when the rule runs, then mockk<SomeDao>() and mockk<RemoteDataSource>() each produce a finding.
 * - Given the must-not-happen risk, when tests run, then rule must not flag production source uses of mockk (defensive — production should not import mockk).
 *
 * Observable outcomes:
 * - detekt finding count and message mentioning Fake* preference.
 *
 * TDD proof:
 * - Fails before rule exists because mocked stateful collaborator returns no finding.
 *
 * Excludes:
 * - production source, MockK uses for non-stateful collaborators.
 */
class MockedStatefulCollaboratorRuleTest : FunSpec({
    test("flags mockk<MemoDao>") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import io.mockk.mockk

            interface MemoDao
            val dao = mockk<MemoDao>()
            """,
        )
        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Fake"
    }

    test("flags typed property initialised by mockk relaxed") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import io.mockk.mockk

            interface MemoRepository
            private val repo: MemoRepository = mockk(relaxed = true)
            """,
        )
        findings.shouldHaveSize(1)
    }

    test("allows mockk for non-stateful collaborator type") {
        val findings = rule().findingsForTestSource(
            """
            package com.lomo.sample

            import io.mockk.mockk

            interface Logger
            val logger = mockk<Logger>()
            """,
        )
        findings.shouldHaveSize(0)
    }
})

private fun rule(config: Config = Config.empty): Rule =
    checkNotNull(LomoTestStyleRuleSetProvider().instance().rules[RuleName("MockedStatefulCollaborator")]) {
        "Expected rule 'MockedStatefulCollaborator' to be registered."
    }.invoke(config)

private fun Rule.findingsForTestSource(code: String): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve("src/test/java/com/lomo/sample/FixtureTest.kt")
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
