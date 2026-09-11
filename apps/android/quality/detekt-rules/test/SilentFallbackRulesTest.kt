package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
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
 * - Unit under test: NoSilentCatchFallbackRule, NoSwallowedThrowableRule
 * - Owning layer: quality (silent fallback and error masking prevention)
 * - Priority tier: P0
 *
 * Capability:
 * - NoSilentCatchFallbackRule: rejects catch blocks that are empty or silently return null/empty collections/zero without behavior-contract comment.
 * - NoSwallowedThrowableRule: rejects catch (t: Throwable) in production code unless immediately rethrown or annotated with behavior-contract.
 *
 * Scenarios:
 * - Given an empty catch or a catch that returns null/empty/zero, when linted, then silent fallback is reported.
 * - Given catch (t: Throwable) that neither rethrows nor records a behavior-contract, when linted, then swallowing is reported.
 *
 * Observable outcomes:
 * - Registered rule presence and finding counts for compiled fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because empty catch and Throwable swallow compile without findings.
 *
 * Excludes:
 * - Runtime exception mapping and UI error presentation.
 */
class SilentFallbackRulesTest : FunSpec({
    test("registers NoSilentCatchFallback and NoSwallowedThrowable in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoSilentCatchFallback")].shouldNotBeNull()
        rules[RuleName("NoSwallowedThrowable")].shouldNotBeNull()
    }

    test("NoSilentCatchFallback: flags empty catch block") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun parse() {
                        try {
                            doSomething()
                        } catch (e: Exception) {
                        }
                    }
                    private fun doSomething() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Empty catch block"
    }

    test("NoSilentCatchFallback: flags catch block returning null") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun parse(): String? =
                        try {
                            doSomething()
                        } catch (e: Exception) {
                            null
                        }
                    private fun doSomething(): String = "test"
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Silent catch fallback"
    }

    test("NoSilentCatchFallback: flags catch block returning emptyList()") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun getItems(): List<String> =
                        try {
                            fetch()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    private fun fetch(): List<String> = listOf("a")
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Silent catch fallback"
    }

    test("NoSilentCatchFallback: flags catch block returning empty string") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun getText(): String =
                        try {
                            fetch()
                        } catch (e: Exception) {
                            ""
                        }
                    private fun fetch(): String = "ok"
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Silent catch fallback"
    }

    test("NoSilentCatchFallback: flags catch block returning 0") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun getCount(): Int =
                        try {
                            calculate()
                        } catch (e: Exception) {
                            0
                        }
                    private fun calculate(): Int = 42
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Silent catch fallback"
    }

    test("NoSilentCatchFallback: allows catch block that rethrows") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun compute() {
                        try {
                            calculate()
                        } catch (e: Exception) {
                            println("error: " + e.message)
                            throw e
                        }
                    }
                    private fun calculate(): Int = 42
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoSilentCatchFallback: allows catch block with behavior-contract comment") {
        val findings =
            rule("NoSilentCatchFallback").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun parse(): String? =
                        try {
                            doSomething()
                        } catch (e: Exception) {
                            // behavior-contract: silent-result-ok: legacy fallback
                            null
                        }
                    private fun doSomething(): String = "test"
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoSwallowedThrowable: flags catch(t: Throwable)") {
        val findings =
            rule("NoSwallowedThrowable").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun run() {
                        try {
                            calculate()
                        } catch (t: Throwable) {
                            println("caught throwable")
                        }
                    }
                    private fun calculate(): Int = 42
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Catching Throwable directly is forbidden"
    }

    test("NoSwallowedThrowable: allows catch(t: Throwable) when immediately rethrown") {
        val findings =
            rule("NoSwallowedThrowable").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun run() {
                        try {
                            calculate()
                        } catch (t: Throwable) {
                            cleanup()
                            throw t
                        }
                    }
                    private fun calculate(): Int = 42
                    private fun cleanup() {}
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoSwallowedThrowable: allows catch(t: Throwable) with behavior-contract comment") {
        val findings =
            rule("NoSwallowedThrowable").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun run() {
                        try {
                            calculate()
                        } catch (t: Throwable) {
                            // behavior-contract: silent-result-ok: crash containment harness
                            cleanup()
                        }
                    }
                    private fun calculate(): Int = 42
                    private fun cleanup() {}
                }
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

private fun Rule.findingsForSource(
    relativePath: String,
    code: String,
): List<Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
