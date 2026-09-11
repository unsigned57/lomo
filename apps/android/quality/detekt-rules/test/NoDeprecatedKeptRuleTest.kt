package com.lomo.detektrules

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

/*
 * Behavior Contract:
 * - Unit under test: NoDeprecatedKeptRule
 * - Owning layer: quality (custom detekt rule)
 * - Priority tier: P0 — forbids soft-migration landing pads. `@Deprecated` is the most common
 *   form of "migration tail" left behind by half-finished refactors, and no existing rule covers it.
 *
 * Capability: forbid any `@Deprecated` annotation in production source (`/src/`). The
 * repository has no public SDK API, so every `@Deprecated` is a migration tail that must be
 * replaced by completing the migration (delete the old API and update every call site in the
 * same change).
 *
 * Scenarios:
 * - Given `@Deprecated` on a top-level function in production source, when the rule runs,
 *   then it reports one finding citing the soft-migration ban.
 * - Given `@Deprecated` on a class declaration in production source, when the rule runs,
 *   then it reports one finding.
 * - Given `@Deprecated` on a property in production source, when the rule runs,
 *   then it reports one finding.
 * - Given a fully qualified `@kotlin.Deprecated` in production source, when the rule runs,
 *   then it reports one finding (short-name match still catches the qualified form).
 * - Given a production source with no annotations, when the rule runs, then it reports
 *   no finding.
 * - Given the same `@Deprecated` annotation in `/test/`, when the rule runs, then it
 *   reports no finding (production-only scope).
 *
 * Observable outcomes:
 * - detekt finding count matches expected; finding message contains "@Deprecated".
 *
 * TDD proof:
 * - Before the rule is registered: the registration test throws
 *   `IllegalStateException: Expected rule 'NoDeprecatedKept' to be registered.`
 *   and every positive-finding scenario fails its `shouldHaveSize(1)` assertion.
 * - After implementation: registration succeeds and all positive/negative assertions hold.
 *
 * Excludes:
 * - Test source — `@Deprecated` is acceptable in test fixtures that exercise legacy APIs.
 * - `@Deprecated` retained intentionally for a published SDK API (this repo has none today;
 *   add a path allowlist in the rule config if the future need arises).
 
 * Test Change Justification:
 * - Reason category: mechanical layout path update.
 * - Old behavior/assertion being replaced: fixture relativePath strings used maven-like or com/lomo-rooted source paths.
 * - Why old assertion is no longer correct: product modules omit the common package root on disk under Amper src/test roots.
 * - Coverage preserved by: same Detekt finding contracts and assertion messages.
 * - Why this is not fitting the test to the implementation: only path fixtures changed; rule behavior is unchanged.
*/
class NoDeprecatedKeptRuleTest : FunSpec({
    test("registers NoDeprecatedKept in the rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoDeprecatedKept")].shouldNotBeNull()
    }

    test("flags @Deprecated on a top-level function in production source") {
        val findings =
            rule("NoDeprecatedKept").findingsForMainSource(
                """
                package com.lomo.sample

                @Deprecated("use newApi")
                fun oldApi(): Int = 1
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "@Deprecated"
    }

    test("flags @Deprecated on a class declaration in production source") {
        val findings =
            rule("NoDeprecatedKept").findingsForMainSource(
                """
                package com.lomo.sample

                @Deprecated("use NewWidget")
                class OldWidget
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("flags @Deprecated on a property in production source") {
        val findings =
            rule("NoDeprecatedKept").findingsForMainSource(
                """
                package com.lomo.sample

                @Deprecated("use newValue")
                val oldValue: Int = 1
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("flags fully qualified @kotlin.Deprecated in production source") {
        val findings =
            rule("NoDeprecatedKept").findingsForMainSource(
                """
                package com.lomo.sample

                @kotlin.Deprecated("legacy")
                fun legacy(): Int = 0
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("does not flag production source without @Deprecated") {
        val findings =
            rule("NoDeprecatedKept").findingsForMainSource(
                """
                package com.lomo.sample

                fun fresh(): Int = 1
                """,
            )

        findings shouldBe emptyList()
    }

    test("does not flag @Deprecated in test source") {
        val findings =
            rule("NoDeprecatedKept").findingsForTestSource(
                """
                package com.lomo.sample

                @Deprecated("used by legacy fixture")
                fun legacyFixture(): Int = 0
                """,
            )

        findings shouldBe emptyList()
    }
})

private fun rule(
    name: String,
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(name)]) {
        "Expected rule '$name' to be registered."
    }.invoke(config)

private fun Rule.findingsForMainSource(code: String) =
    findingsForSource("src/sample/Fixture.kt", code)

private fun Rule.findingsForTestSource(code: String) =
    findingsForSource("test/sample/Fixture.kt", code)

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
