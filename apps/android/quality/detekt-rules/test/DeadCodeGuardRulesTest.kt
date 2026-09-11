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
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: LomoArchitectureRuleSetProvider dead-code guard rules.
 * - Behavior focus: literal branch rejection, unreachable tail detection, redundant exhaustive else detection, duplicate helper detection, and module-local dead declaration detection.
 * - Observable outcomes: finding count, finding message content, duplicate declaration reporting, unreferenced declaration reporting, and src-only enforcement.
 * - TDD proof: Fails before the fix because the new duplicate/dead-declaration rules are not yet registered and therefore do not report the cross-file regressions.
 * - Excludes: detekt engine integration, Toolchain task wiring, compiler-native diagnostics outside rule execution, and cross-module dead-code analysis for public APIs.
 */
class DeadCodeGuardRulesTest : FunSpec() {
    init {
    test("registers dead-code guard rules in the rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules

        rules[RuleName("NoConstantBranchCondition")].shouldNotBeNull()
        rules[RuleName("NoUnreachableBlockTail")].shouldNotBeNull()
        rules[RuleName("NoRedundantExhaustiveElse")].shouldNotBeNull()
        rules[RuleName("NoCrossFileDuplicateTopLevel")].shouldNotBeNull()
        rules[RuleName("NoUnreferencedTopLevelDeclaration")].shouldNotBeNull()
    }

    test("reports literal if conditions in production source") {
        val findings =
            rule("NoConstantBranchCondition").findingsForMainSource(
                """
                package com.lomo.sample

                fun deadBranch(flag: Boolean): Int {
                    if (true) {
                        return 1
                    }
                    return if (flag) 2 else 3
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "always"
    }

    test("ignores runtime conditions in production source") {
        val findings =
            rule("NoConstantBranchCondition").findingsForMainSource(
                """
                package com.lomo.sample

                fun reachableBranch(flag: Boolean): Int =
                    if (flag) 1 else 2
                """,
            )

        findings shouldBe emptyList()
    }

    test("reports statements after return in the same block") {
        val findings =
            rule("NoUnreachableBlockTail").findingsForMainSource(
                """
                package com.lomo.sample

                fun unreachableTail(): Int {
                    return 1
                    val dead = 2
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Unreachable"
    }

    test("reports redundant else after boolean when is already exhaustive") {
        val findings =
            rule("NoRedundantExhaustiveElse").findingsForMainSource(
                """
                package com.lomo.sample

                fun booleanWhen(flag: Boolean): String =
                    when (flag) {
                        true -> "yes"
                        false -> "no"
                        else -> "unused"
                    }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "else"
    }

    test("ignores production guards in test source paths") {
        val findings =
            rule("NoConstantBranchCondition").findingsForSource(
                relativePath = "test/sample/DeadCodeGuardRulesFixtureTest.kt",
                code =
                    """
                    package com.lomo.sample

                    fun testOnlyFixture(): Int {
                        if (true) {
                            return 1
                        }
                        return 2
                    }
                """,
            )

        findings shouldBe emptyList()
    }

    test("allows configured no-source-suppressions path exceptions") {
        val findings =
            rule(
                name = "NoSourceSuppressions",
                config =
                    TestConfig(
                        "excludes" to listOf("ui/component/input/InputSheetFocusEffects.kt"),
                    ),
            ).findingsForSource(
                relativePath = "src/ui/component/input/InputSheetFocusEffects.kt",
                code =
                    """
                    package com.lomo.ui.component.input

                    @Suppress("DEPRECATION")
                    private const val sample = 1
                """,
            )

        findings shouldBe emptyList()
    }

    test("still reports no-source-suppressions outside configured exceptions") {
        val findings =
            rule(
                name = "NoSourceSuppressions",
                config =
                    TestConfig(
                        "excludes" to listOf("ui/component/input/InputSheetFocusEffects.kt"),
                    ),
            ).findingsForMainSource(
                """
                package com.lomo.sample

                @Suppress("DEPRECATION")
                private const val sample = 1
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("reports duplicate top level functions across production files") {
        val findings =
            rule("NoCrossFileDuplicateTopLevel").findingsForSources(
                "src/sample/First.kt" to
                    """
                    package com.lomo.sample

                    internal fun dpToPx(value: Int): Int = value * 2
                    """,
                "src/sample/Second.kt" to
                    """
                    package com.lomo.sample

                    internal fun dpToPx(value: Int): Int = value * 2
                    """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Duplicate top-level declaration"
    }

    test("ignores duplicate top level functions when only test sources are involved") {
        val findings =
            rule("NoCrossFileDuplicateTopLevel").findingsForSources(
                "src/sample/First.kt" to
                    """
                    package com.lomo.sample

                    internal fun dpToPx(value: Int): Int = value * 2
                    """,
                "test/sample/FirstTest.kt" to
                    """
                    package com.lomo.sample

                    internal fun dpToPx(value: Int): Int = value * 2
                """,
            )

        findings shouldBe emptyList()
    }

    test("reports unreferenced non public top level declarations across production files") {
        val findings =
            rule("NoUnreferencedTopLevelDeclaration").findingsForSources(
                "src/sample/UnusedHelper.kt" to
                    """
                    package com.lomo.sample

                    internal fun unreachableHelper(): Int = 7
                    """,
                "src/sample/Consumer.kt" to
                    """
                    package com.lomo.sample

                    internal fun consumer(): Int = 1
                """,
            )

        findings.shouldHaveSize(2)
        findings.all { it.message.contains("Unreferenced top-level declaration") }.shouldBeTrue()
    }

    test("keeps referenced non public top level declarations") {
        val findings =
            rule("NoUnreferencedTopLevelDeclaration").findingsForSources(
                "src/sample/Helpers.kt" to
                    """
                    package com.lomo.sample

                    internal fun reachableHelper(): Int = 7
                    """,
                "src/sample/Consumer.kt" to
                    """
                    package com.lomo.sample

                    fun consumer(): Int = reachableHelper()
                """,
            )

        findings shouldBe emptyList()
    }
    }

    private fun rule(
        name: String,
        config: Config = Config.empty,
    ): Rule =
        checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(name)]) {
            "Expected rule '$name' to be registered."
        }.invoke(config)

    private fun Rule.findingsForMainSource(code: String) = findingsForSource("src/sample/Fixture.kt", code)

    private fun Rule.findingsForSource(
        relativePath: String,
        code: String,
    ): List<dev.detekt.api.Finding> {
        val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
        val file = tempDir.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(code.trimIndent())
        return lint(compileForTest(file))
    }

    private fun Rule.findingsForSources(vararg fixtures: Pair<String, String>): List<dev.detekt.api.Finding> {
        val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
        val files =
            fixtures.map { (relativePath, code) ->
                tempDir.resolve(relativePath).also { file ->
                    file.parent.createDirectories()
                    file.writeText(code.trimIndent())
                }
            }
        return files
            .map(::compileForTest)
            .flatMap { file -> visitFile(file, LanguageVersionSettingsImpl.DEFAULT) }
    }
}
