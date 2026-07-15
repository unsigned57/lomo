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
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: NoDispatchersSetMainInTestsRule
 * - Owning layer: quality
 * - Priority tier: P2
 *
 * Scenarios:
 * - Given the happy path, when the rule runs, then tests using MainDispatcherExtension produce no finding.
 * - Given the boundary path, when the rule runs, then a Dispatchers.setMain call wrapped behind the extension class itself is excluded by path.
 * - Given the failure path, when the rule runs, then a feature test calling Dispatchers.setMain directly is flagged.
 * - Given the must-not-happen risk, when tests run, then never flag production source uses.
 *
 * Observable outcomes:
 * - detekt finding referencing MainDispatcherExtension.
 *
 * TDD proof:
 * - Fails before rule exists because direct Dispatchers.setMain in test code returns no finding.
 *
 * Excludes:
 * - the MainDispatcherExtension implementation file itself.
 */
class NoDispatchersSetMainInTestsRuleTest : FunSpec({
    test("flags Dispatchers.setMain in feature test source") {
        val findings = rule().findingsForTestSource(
            "test/sample/FeatureTest.kt",
            """
            package com.lomo.sample

            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.test.StandardTestDispatcher

            fun setup() {
                Dispatchers.setMain(StandardTestDispatcher())
            }
            """,
        )
        findings.shouldHaveSize(1)
    }

    test("allows production source uses") {
        val findings = rule().findingsForTestSource(
            "src/sample/Setup.kt",
            """
            package com.lomo.sample

            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.test.StandardTestDispatcher

            fun setup() {
                Dispatchers.setMain(StandardTestDispatcher())
            }
            """,
        )
        findings.shouldHaveSize(0)
    }

    test("allows MainDispatcherExtension implementation file itself") {
        val findings = rule().findingsForTestSource(
            "test/testing/MainDispatcherExtension.kt",
            """
            package com.lomo.app.testing

            import kotlinx.coroutines.Dispatchers

            class MainDispatcherExtension {
                fun bind() { Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher()) }
            }
            """,
        )
        findings.shouldHaveSize(0)
    }
})

private fun rule(config: Config = Config.empty): Rule =
    checkNotNull(LomoTestStyleRuleSetProvider().instance().rules[RuleName("NoDispatchersSetMainInTests")]) {
        "Expected rule 'NoDispatchersSetMainInTests' to be registered."
    }.invoke(config)

private fun Rule.findingsForTestSource(relativePath: String, code: String): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
