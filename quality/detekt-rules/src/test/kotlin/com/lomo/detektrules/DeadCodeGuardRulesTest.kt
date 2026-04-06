package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Test Contract:
 * - Unit under test: LomoArchitectureRuleSetProvider dead-code guard rules.
 * - Behavior focus: literal branch rejection, unreachable tail detection, redundant exhaustive else detection.
 * - Observable outcomes: finding count, finding message content, src/main-only enforcement.
 * - Red phase: Fails before the fix because the rule set does not yet expose dead-code guard rules, so these findings are missing.
 * - Excludes: detekt engine integration, Gradle task wiring, compiler-native diagnostics outside rule execution.
 */
class DeadCodeGuardRulesTest {
    @Test
    fun `registers dead-code guard rules in the rule set`() {
        val rules = LomoArchitectureRuleSetProvider().instance().rules

        assertNotNull(rules[RuleName("NoConstantBranchCondition")])
        assertNotNull(rules[RuleName("NoUnreachableBlockTail")])
        assertNotNull(rules[RuleName("NoRedundantExhaustiveElse")])
    }

    @Test
    fun `reports literal if conditions in production source`() {
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

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("always"))
    }

    @Test
    fun `ignores runtime conditions in production source`() {
        val findings =
            rule("NoConstantBranchCondition").findingsForMainSource(
                """
                package com.lomo.sample

                fun reachableBranch(flag: Boolean): Int =
                    if (flag) 1 else 2
                """,
            )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `reports statements after return in the same block`() {
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

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("Unreachable"))
    }

    @Test
    fun `reports redundant else after boolean when is already exhaustive`() {
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

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("else"))
    }

    @Test
    fun `ignores production guards in test source paths`() {
        val findings =
            rule("NoConstantBranchCondition").findingsForSource(
                relativePath = "src/test/kotlin/com/lomo/sample/DeadCodeGuardRulesFixtureTest.kt",
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

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `allows configured no-source-suppressions path exceptions`() {
        val findings =
            rule(
                name = "NoSourceSuppressions",
                config =
                    TestConfig(
                        "excludes" to listOf("ui/component/input/InputSheetFocusEffects.kt"),
                    ),
            ).findingsForSource(
                relativePath = "src/main/java/com/lomo/ui/component/input/InputSheetFocusEffects.kt",
                code =
                    """
                    package com.lomo.ui.component.input

                    @Suppress("DEPRECATION")
                    private const val sample = 1
                    """,
            )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `still reports no-source-suppressions outside configured exceptions`() {
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

        assertEquals(1, findings.size)
    }

    private fun rule(
        name: String,
        config: Config = Config.empty,
    ): Rule =
        checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(name)]) {
            "Expected rule '$name' to be registered."
        }.invoke(config)

    private fun Rule.findingsForMainSource(code: String) = findingsForSource("src/main/java/com/lomo/sample/Fixture.kt", code)

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
}
