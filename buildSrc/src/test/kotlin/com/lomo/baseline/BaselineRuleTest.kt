package com.lomo.baseline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/*
 * Test Contract:
 * - Unit under test: BaselineRule in the build-system layer.
 * - Behavior focus: parse baseline-rules lines, strip comments and blank lines, reject invalid flags, and
 *   match JVM internal-name globs with * and ** semantics.
 * - Observable outcomes: parsed rule count/flags, Boolean match results, and IllegalArgumentException for
 *   malformed flags.
 * - Red phase: Fails before the fix because BaselineRule does not exist yet and later because parsing or
 *   matching behavior is missing or incorrect.
 * - Excludes: Gradle variant wiring, classpath scanning, and profile-file generation.
 */
class BaselineRuleTest : FunSpec({
    test("parse strips comments blank lines and derives class flags") {
        val rules =
            BaselineRule.parse(
                """
                # comment only
                HSPL com/example/Foo # trailing comment

                SP com/example/*/Feature
                """.trimIndent(),
            )

        rules.size shouldBe 2
        rules[0].classFlags shouldBe "L"
        rules[0].methodFlags shouldBe "HSPL"
        rules[1].classFlags shouldBe ""
        rules[1].methodFlags shouldBe "SP"
        rules[0].matches("com/example/Foo") shouldBe true
        rules[1].matches("com/example/ui/Feature") shouldBe true
    }

    test("parse rejects invalid flags") {
        val error =
            shouldThrow<IllegalArgumentException> {
                BaselineRule.parse("HX com/example/Foo")
            }

        error.message.orEmpty() shouldContain "Invalid flags"
    }

    test("double star matches across package depth") {
        val rule = BaselineRule.parse("HSPL com/example/**").single()

        rule.matches("com/example/Foo") shouldBe true
        rule.matches("com/example/deep/nested/Foo") shouldBe true
    }

    test("single star matches one path segment only") {
        val rule = BaselineRule.parse("HSPL com/example/*/Feature").single()

        rule.matches("com/example/ui/Feature") shouldBe true
        rule.matches("com/example/ui/deep/Feature") shouldBe false
    }

    test("matches returns false when internal name does not satisfy pattern") {
        val rule = BaselineRule.parse("HSPL com/example/Foo").single()

        rule.matches("com/example/Bar") shouldBe false
    }
})
