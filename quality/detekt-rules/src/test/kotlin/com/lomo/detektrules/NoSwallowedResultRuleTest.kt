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
 * - Unit under test: NoSwallowedResultRule
 * - Owning layer: quality (custom detekt rule)
 * - Priority tier: P0 — closes a real gap: detekt's SwallowedException only covers try/catch,
 *   and the Result API form (runCatching{}.getOrNull()) silently bypasses it.
 *
 * Capability: forbid silently discarding the kotlin.Result returned by `runCatching` when the
 * chain terminates in `.getOrNull()`, `.getOrDefault(<literal/empty>)`, or
 * `.getOrElse { <literal/empty> }` AND the chain contains no observability operator
 * (`onFailure`, `recover`, `recoverCatching`).
 *
 * Scenarios:
 * - Given `runCatching {}.getOrNull()` in production source, when the rule runs, then it reports
 *   one finding citing "swallowed Result".
 * - Given `runCatching {}.getOrDefault(emptyList())` in production source, when the rule runs,
 *   then it reports one finding.
 * - Given `runCatching {}.getOrElse { null }` in production source, when the rule runs,
 *   then it reports one finding.
 * - Given `runCatching {}.map { it }.getOrNull()` in production source, when the rule runs,
 *   then it reports one finding (map does not make failure observable).
 * - Given `runCatching {}.getOrThrow()` in production source, when the rule runs,
 *   then it reports no finding (re-throws on failure, not silent).
 * - Given `runCatching {}.fold(onSuccess = ..., onFailure = ...)` in production source,
 *   when the rule runs, then it reports no finding (explicit handling).
 * - Given `runCatching {}.onFailure { log(it) }.getOrNull()` in production source,
 *   when the rule runs, then it reports no finding (failure is observable).
 * - Given `runCatching {}.recover { fallback() }.getOrNull()` in production source,
 *   when the rule runs, then it reports no finding (failure is recovered).
 * - Given `runCatching {}.getOrDefault(computeFallback())` (non-literal arg) in production source,
 *   when the rule runs, then it reports no finding (fallback is a real computation).
 * - Given `runCatching {}.getOrElse { handle(it); null }` (side-effecting lambda body) in
 *   production source, when the rule runs, then it reports no finding.
 * - Given a `// behavior-contract: silent-result-ok: <reason>` comment on the line immediately
 *   above the `runCatching` chain, when the rule runs, then it reports no finding (documented
 *   legitimate exception).
 * - Given a same-line trailing `// behavior-contract: silent-result-ok: <reason>` comment on
 *   the line containing `runCatching`, when the rule runs, then it reports no finding.
 * - Given a `// behavior-contract:` comment that lacks the `silent-result-ok` marker, when
 *   the rule runs, then it still reports (the marker must be explicit).
 * - Given the same offending pattern in `/src/test/`, when the rule runs, then it reports
 *   no finding (production-only scope).
 *
 * Observable outcomes:
 * - detekt finding count matches expected per scenario; finding message contains "swallowed Result".
 *
 * TDD proof:
 * - Before the rule is registered: the registration test throws
 *   `IllegalStateException: Expected rule 'NoSwallowedResult' to be registered.`
 *   and every positive-finding scenario fails its `shouldHaveSize(1)` assertion.
 * - After implementation: registration succeeds and all positive/negative assertions hold.
 *
 * Excludes:
 * - Plain `try { ... } catch { ... }` swallowing — already covered by detekt's built-in
 *   `SwallowedException`.
 * - Terminals that re-throw (`getOrThrow`) or inspect the exception (`exceptionOrNull`).
 * - Result chains whose terminal value is mapped onward (chain does not end in a silent
 *   value extractor).
 */
class NoSwallowedResultRuleTest : FunSpec({
    test("registers NoSwallowedResult in the rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoSwallowedResult")].shouldNotBeNull()
    }

    test("flags runCatching followed by getOrNull in production source") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? = runCatching { compute() }.getOrNull()

                private fun compute(): String = "ok"
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Swallowed Result"
    }

    test("flags runCatching followed by getOrDefault with empty list literal") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun loadList(): List<String> =
                    runCatching { compute() }.getOrDefault(emptyList())

                private fun compute(): List<String> = listOf("ok")
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("flags runCatching followed by getOrElse with constant lambda body") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun loadOrNull(): String? =
                    runCatching { compute() }.getOrElse { null }

                private fun compute(): String = "ok"
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("flags runCatching followed by map then getOrNull (map does not observe failure)") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? =
                    runCatching { compute() }.map { it + "!" }.getOrNull()

                private fun compute(): String = "ok"
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("ignores runCatching ending in getOrThrow") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String = runCatching { compute() }.getOrThrow()

                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores runCatching followed by fold") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String = runCatching { compute() }.fold(
                    onSuccess = { it },
                    onFailure = { "fallback" },
                )

                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores runCatching with onFailure before silent terminal") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? =
                    runCatching { compute() }
                        .onFailure { log(it) }
                        .getOrNull()

                private fun log(t: Throwable) { t.printStackTrace() }
                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores runCatching followed by recover before silent terminal") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? =
                    runCatching { compute() }
                        .recover { fallback() }
                        .getOrNull()

                private fun fallback(): String = "fallback"
                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores getOrDefault with non-literal argument (real fallback computation)") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String = runCatching { compute() }.getOrDefault(computeFallback())

                private fun computeFallback(): String = "f"
                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores getOrElse with side-effecting lambda body") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? =
                    runCatching { compute() }.getOrElse { t -> handle(t); null }

                private fun handle(t: Throwable) { t.printStackTrace() }
                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores violation with behavior-contract opt-out comment on preceding line") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? {
                    // behavior-contract: silent-result-ok: caller deliberately accepts null on parse failure
                    return runCatching { compute() }.getOrNull()
                }

                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("ignores violation with same-line trailing behavior-contract opt-out comment") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? =
                    runCatching { compute() }.getOrNull() // behavior-contract: silent-result-ok: format mismatch is normal

                private fun compute(): String = "ok"
                """,
            )

        findings shouldBe emptyList()
    }

    test("still flags violation when preceding comment lacks the silent-result-ok marker") {
        val findings =
            rule("NoSwallowedResult").findingsForMainSource(
                """
                package com.lomo.sample

                fun load(): String? {
                    // behavior-contract: returns parsed value or null
                    return runCatching { compute() }.getOrNull()
                }

                private fun compute(): String = "ok"
                """,
            )

        findings.shouldHaveSize(1)
    }

    test("does not flag offending pattern in test source") {
        val findings =
            rule("NoSwallowedResult").findingsForTestSource(
                """
                package com.lomo.sample

                fun load(): String? = runCatching { compute() }.getOrNull()

                private fun compute(): String = "ok"
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
    findingsForSource("src/main/java/com/lomo/sample/Fixture.kt", code)

private fun Rule.findingsForTestSource(code: String) =
    findingsForSource("src/test/java/com/lomo/sample/Fixture.kt", code)

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
