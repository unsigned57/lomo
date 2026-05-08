package com.lomo.baseline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

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
class BaselineRuleTest {
    @Test
    fun `parse strips comments blank lines and derives class flags`() {
        val rules =
            BaselineRule.parse(
                """
                # comment only
                HSPL com/example/Foo # trailing comment

                SP com/example/*/Feature
                """.trimIndent(),
            )

        assertEquals(2, rules.size)
        assertEquals("L", rules[0].classFlags)
        assertEquals("HSPL", rules[0].methodFlags)
        assertEquals("", rules[1].classFlags)
        assertEquals("SP", rules[1].methodFlags)
        assertTrue(rules[0].matches("com/example/Foo"))
        assertTrue(rules[1].matches("com/example/ui/Feature"))
    }

    @Test
    fun `parse rejects invalid flags`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                BaselineRule.parse("HX com/example/Foo")
            }

        assertTrue(error.message.orEmpty().contains("Invalid flags"))
    }

    @Test
    fun `double star matches across package depth`() {
        val rule = BaselineRule.parse("HSPL com/example/**").single()

        assertTrue(rule.matches("com/example/Foo"))
        assertTrue(rule.matches("com/example/deep/nested/Foo"))
    }

    @Test
    fun `single star matches one path segment only`() {
        val rule = BaselineRule.parse("HSPL com/example/*/Feature").single()

        assertTrue(rule.matches("com/example/ui/Feature"))
        assertFalse(rule.matches("com/example/ui/deep/Feature"))
    }

    @Test
    fun `matches returns false when internal name does not satisfy pattern`() {
        val rule = BaselineRule.parse("HSPL com/example/Foo").single()

        assertFalse(rule.matches("com/example/Bar"))
    }
}
