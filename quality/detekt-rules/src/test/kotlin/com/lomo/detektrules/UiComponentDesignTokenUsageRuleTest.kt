package com.lomo.detektrules

/*
 * Behavior Contract:
 * - Unit under test: UiComponentDesignTokenUsage Detekt rule.
 * - Owning layer: quality/detekt-rules
 * - Priority tier: P1
 * - Capability: keep shared Compose components on semantic design tokens instead of local visual constants.
 *
 * Scenarios:
 * - Given ui-components production component code creates a static RoundedCornerShape or alpha ramp locally,
 *   when Detekt runs, then the rule reports the bypass.
 * - Given a component token file centralizes the same primitives, when Detekt runs, then the rule allows it.
 * - Given component code derives shape or alpha from runtime state, when Detekt runs, then the rule does not
 *   block dynamic interaction state.
 *
 * Observable outcomes:
 * - Registered rule presence, finding count, and finding message content for component source fixtures.
 *
 * TDD proof:
 * - RED: this test fails before the fix because UiComponentDesignTokenUsage is not registered.
 *
 * Excludes:
 * - Full Toolchain static-analysis task wiring, type resolution, rendering output, and app-feature token policy.
 
 * Test Change Justification:
 * - Reason category: mechanical layout path update.
 * - Old behavior/assertion being replaced: fixture relativePath strings used maven-like or com/lomo-rooted source paths.
 * - Why old assertion is no longer correct: product modules omit the common package root on disk under Amper src/test roots.
 * - Coverage preserved by: same Detekt finding contracts and assertion messages.
 * - Why this is not fitting the test to the implementation: only path fixtures changed; rule behavior is unchanged.
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
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class UiComponentDesignTokenUsageRuleTest : FunSpec({
    test("registers ui component design-token usage rule in the rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules

        rules[RuleName("UiComponentDesignTokenUsage")].shouldNotBeNull()
    }

    test("reports static shape and alpha bypasses in ui component source") {
        val findings =
            rule().findingsForSource(
                relativePath = "ui-components/src/component/card/BadCard.kt",
                code =
                    """
                    package com.lomo.ui.component.card

                    import androidx.compose.foundation.shape.RoundedCornerShape
                    import androidx.compose.ui.graphics.Color
                    import androidx.compose.ui.unit.dp

                    private val BAD_SHAPE = RoundedCornerShape(12.dp)
                    private const val BAD_ALPHA = 0.5f

                    fun badAlpha(color: Color): Color = color.copy(alpha = BAD_ALPHA)

                    fun literalAlpha(color: Color): Color = color.copy(alpha = 0.3f)
                    """,
            )

        findings.shouldHaveSize(3)
        findings[0].message shouldContain "semantic component token"
        findings[1].message shouldContain "alpha"
        findings[2].message shouldContain "alpha"
    }

    test("allows component token files to centralize static visual primitives") {
        val findings =
            rule().findingsForSource(
                relativePath = "ui-components/src/component/card/MemoCardTokens.kt",
                code =
                    """
                    package com.lomo.ui.component.card

                    import androidx.compose.foundation.shape.RoundedCornerShape
                    import androidx.compose.ui.graphics.Color
                    import androidx.compose.ui.unit.dp

                    private val TOKEN_SHAPE = RoundedCornerShape(12.dp)
                    private const val TOKEN_ALPHA = 0.5f

                    fun tokenColor(color: Color): Color = color.copy(alpha = TOKEN_ALPHA)
                    """,
            )

        findings shouldBe emptyList()
    }

    test("allows runtime shape and alpha state in ui component source") {
        val findings =
            rule().findingsForSource(
                relativePath = "ui-components/src/component/input/DynamicInputSheet.kt",
                code =
                    """
                    package com.lomo.ui.component.input

                    import androidx.compose.foundation.shape.RoundedCornerShape
                    import androidx.compose.ui.graphics.Color
                    import androidx.compose.ui.unit.Dp

                    fun runtimeShape(radius: Dp) = RoundedCornerShape(radius)

                    fun runtimeAlpha(color: Color, animatedAlpha: Float): Color = color.copy(alpha = animatedAlpha)
                    """,
            )

        findings shouldBe emptyList()
    }
})

private fun rule(
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName("UiComponentDesignTokenUsage")]) {
        "Expected UiComponentDesignTokenUsage to be registered."
    }.invoke(config)

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
