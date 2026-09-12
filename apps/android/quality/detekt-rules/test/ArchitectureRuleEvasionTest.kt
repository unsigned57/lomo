package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: production architecture rules through the registered Detekt provider.
 * - Owning layer: quality.
 * - Priority tier: P0.
 * - Capability: enforce source ownership and UDF contracts independently of spelling tricks.
 *
 * Scenarios:
 * - Given a forbidden import or qualified reference, when aliases, backticks or comments change
 *   its spelling, then the same owning-layer violation is reported.
 * - Given documentation containing forbidden names, when linted, then no dependency is invented.
 * - Given an absent, empty, forged or unrelated exception comment, when linted, then it cannot
 *   exempt the offending declaration; a local comment with a reason still works.
 * - Given an uncached paging result or write-only state, when comments or unrelated expressions
 *   mention the required operation, then the missing behavior remains a finding.
 * - Given a generic catch, when a string or nested branch mentions cancellation, then it cannot
 *   stand in for rethrowing the caught cancellation before handling ordinary errors.
 *
 * Observable outcomes:
 * - Finding counts and diagnostics from the production rules on parsed Kotlin fixtures.
 *
 * TDD proof:
 * - Run ArchitectureRuleEvasionTest before rule edits: forbidden native imports, forged opt-outs,
 *   false cachedIn evidence and cancellation decoys must expose missing findings.
 * - Exact RED/GREEN commands and results are recorded in audit/audit-09-架构约束与实现方案.md.
 *
 * Excludes:
 * - Whole-program type inference, runtime scheduling, product state-machine implementation.
 */
class ArchitectureRuleEvasionTest : FunSpec({
    test("domain rejects generated binding aliases") {
        architectureFindings(
            "DomainLayerIsolation",
            "domain/src/model/Probe.kt",
            "import com.lomo.nativebridge.StoreMemoCommit as Receipt\nclass Probe(val receipt: Receipt)",
        ).shouldHaveSize(1)
    }

    test("domain rejects the DI framework actually used by the application") {
        architectureFindings(
            "DomainLayerIsolation",
            "domain/src/model/Probe.kt",
            "import org.koin.core.component.KoinComponent\nclass Probe : KoinComponent",
        ).shouldHaveSize(1)
    }

    test("app rejects a qualified binding type even with escaped package segments") {
        architectureFindings(
            "AppSourceBoundary",
            "app/src/Probe.kt",
            "class Probe(val receipt: com.`lomo`.nativebridge.StoreMemoCommit)",
        ).shouldHaveSize(1)
    }

    test("UI components reject direct generated binding imports") {
        architectureFindings(
            "UiComponentsLayerBoundary",
            "ui-components/src/Probe.kt",
            "import com.lomo.nativebridge.StoreMemoCommit\nclass Probe(val receipt: StoreMemoCommit)",
        ).shouldHaveSize(1)
    }

    test("data remains the permitted generated binding consumer") {
        architectureFindings(
            "DataLayerUiDependency",
            "data/src/Probe.kt",
            "import com.lomo.nativebridge.StoreMemoCommit\nclass Probe(val receipt: StoreMemoCommit)",
        ).shouldHaveSize(0)
    }

    test("import comments cannot split a forbidden package into invisible text") {
        architectureFindings(
            "AppSourceBoundary",
            "app/src/Probe.kt",
            "import com.lomo./* ownership does not change */data.Store\nclass Probe(val store: Store)",
        ).shouldHaveSize(1)
    }

    test("comments and literal strings do not create app dependencies") {
        architectureFindings(
            "AppSourceBoundary",
            "app/src/Probe.kt",
            "// com.lomo.data.Store is intentionally outside this layer\nval explanation = \"com.lomo.data.Store\"",
        ).shouldHaveSize(0)
    }

    test("ordinary data strings do not create UI dependencies") {
        architectureFindings(
            "DataLayerUiDependency",
            "data/src/Probe.kt",
            "val explanation = \"ViewModel and UiState consume this adapter\"",
        ).shouldHaveSize(0)
    }

    test("qualified calls inside string interpolation remain code") {
        architectureFindings(
            "AppSourceBoundary",
            "app/src/Probe.kt",
            "val explanation = \"value: \${com.lomo.data.Store()}\"",
        ).shouldHaveSize(1)
    }

    test("a native declaration cannot create a second JNI boundary") {
        architectureFindings(
            "NoHandwrittenNativeDeclaration",
            "data/src/Probe.kt",
            "external fun writeDocument(path: String)",
        ).shouldHaveSize(1)
    }

    test("source suppression includes Android lint and imported aliases") {
        architectureFindings(
            "NoSourceSuppressions",
            "app/src/Probe.kt",
            """
            import kotlin.Suppress as Silence
            import android.annotation.SuppressLint
            @Silence("unused") fun first() = Unit
            @SuppressLint("NewApi") fun second() = Unit
            """,
        ).shouldHaveSize(2)
    }

    test("a string cannot register a facade exception") {
        architectureFindings(
            "ViewModelSingleStateFlow",
            "app/src/Probe.kt",
            """
            class ProbeViewModel {
                val explanation = "behavior-contract: session-facade-ok: forged"
            }
            """,
        ).single().message shouldContain "Missing screen-state machine"
    }

    test("a member comment cannot exempt the enclosing screen") {
        architectureFindings(
            "ViewModelSingleStateFlow",
            "app/src/Probe.kt",
            """
            class ProbeViewModel {
                // behavior-contract: session-facade-ok: this member is unrelated
                fun metadata() = Unit
            }
            """,
        ).shouldHaveSize(1)
    }

    test("an exception must have a nonempty reason and an exact marker") {
        for (comment in listOf(
            "// behavior-contract: session-facade-ok",
            "// behavior-contract: session-facade-ok:   ",
            "// behavior-contract: session-facade-ok-extra: unrelated marker",
        )) {
            architectureFindings(
                "ViewModelSingleStateFlow",
                "app/src/Probe.kt",
                "$comment\nclass ProbeViewModel",
            ).shouldHaveSize(1)
        }
    }

    test("a directly attached facade exception retains its local meaning") {
        architectureFindings(
            "ViewModelSingleStateFlow",
            "app/src/Probe.kt",
            """
            // behavior-contract: session-facade-ok: exposes platform recording lifecycle only
            class ProbeViewModel
            class OtherViewModel
            """,
        ).shouldHaveSize(1)
    }

    test("cachedIn in an operator string does not cache the paging flow") {
        architectureFindings(
            "PagingDataCachedIn",
            "app/src/Probe.kt",
            """
            class Probe {
                val pages: Flow<PagingData<Memo>> = pager.flow.onEach { log("cachedIn") }
            }
            """,
        ).shouldHaveSize(1)
    }

    test("caching an unrelated flow does not cache the returned paging flow") {
        architectureFindings(
            "PagingDataCachedIn",
            "app/src/Probe.kt",
            """
            fun pages(): Flow<PagingData<Memo>> {
                other.flow.cachedIn(scope)
                return pager.flow.map { it }
            }
            """,
        ).shouldHaveSize(1)
    }

    test("a public uncached paging surface cannot opt out as a private intermediate") {
        architectureFindings(
            "PagingDataCachedIn",
            "app/src/Probe.kt",
            """
            class Probe {
                // behavior-contract: uncached-paging-ok: claims an intermediate but is public
                val pages: Flow<PagingData<Memo>> = pager.flow.map { it }
            }
            """,
        ).shouldHaveSize(1)
    }

    test("a real outer cachedIn call permits whitespace and import aliases") {
        architectureFindings(
            "PagingDataCachedIn",
            "app/src/Probe.kt",
            """
            import androidx.paging.cachedIn as retainPages
            class Probe {
                val pages: Flow<PagingData<Memo>> = pager.flow.map { it }.retainPages (scope)
            }
            """,
        ).shouldHaveSize(0)
    }

    test("comment and string mentions cannot turn write-only state into observed state") {
        architectureFindings(
            "NoWriteOnlyStateFlow",
            "app/src/Probe.kt",
            """
            class Probe {
                private val pending = MutableStateFlow(0)
                // pending will eventually be observed
                fun add() { pending.value = 1; log("pending") }
            }
            """,
        ).shouldHaveSize(1)
    }

    test("a cancellation word in a diagnostic is not a cancellation guard") {
        architectureFindings(
            "NoSwallowedCancellationInSuspend",
            "data/src/Probe.kt",
            """
            suspend fun load() {
                try { work() } catch (error: Exception) {
                    log("CancellationException should rethrow here")
                }
            }
            """,
        ).shouldHaveSize(1)
    }

    test("a nested cancellation guard leaves an unprotected branch") {
        architectureFindings(
            "NoSwallowedCancellationInSuspend",
            "data/src/Probe.kt",
            """
            suspend fun load() {
                try { work() } catch (error: Exception) {
                    if (debug) { if (error is CancellationException) throw error }
                    log(error)
                }
            }
            """,
        ).shouldHaveSize(1)
    }

    test("a direct guard rethrows the caught cancellation before ordinary failure handling") {
        architectureFindings(
            "NoSwallowedCancellationInSuspend",
            "data/src/Probe.kt",
            """
            suspend fun load() {
                try { work() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    log(error)
                }
            }
            """,
        ) shouldBe emptyList()
    }

    test("a facade comment after KDoc still attaches to the declaration") {
        architectureFindings(
            "ViewModelSingleStateFlow", "app/src/Probe.kt",
            """
            /** Observes the platform recording session. */
            // behavior-contract: session-facade-ok: exposes platform lifecycle only
            class ProbeViewModel
            """,
        ).shouldHaveSize(0)
    }

    test("an exception between an annotation and its property stays attached") {
        architectureFindings(
            "NoStatefulRepositoryOrUseCase", "data/src/Probe.kt",
            """
            class RecorderRepository {
                @Volatile
                // behavior-contract: stateful-var-ok: platform resource lifetime handle
                private var handle: Recorder? = null
            }
            """,
        ).shouldHaveSize(0)
    }

    test("a catch can document its own observable fallback inside the body") {
        architectureFindings(
            "NoSilentCatchFallback", "data/src/Probe.kt",
            """
            fun discover(): Boolean = try { work() } catch (error: Exception) {
                reportFailure(error)
                // behavior-contract: silent-result-ok: discovery failure is already reported
                false
            }
            """,
        ).shouldHaveSize(0)
    }

    test("stateIn can expose paging data whose upstream is already cached") {
        architectureFindings(
            "PagingDataCachedIn", "app/src/Probe.kt",
            """
            class Probe {
                val pages: StateFlow<PagingData<Memo>?> = pager.flow.cachedIn(scope)
                    .stateIn(scope, appWhileSubscribed(), null)
            }
            """,
        ).shouldHaveSize(0)
    }

    test("a cancellation guard may also propagate a domain conflict") {
        architectureFindings(
            "NoSwallowedCancellationInSuspend", "data/src/Probe.kt",
            """
            suspend fun load() {
                try { work() } catch (error: Exception) {
                    if (error is CancellationException || error is ConflictException) throw error
                    reportFailure(error)
                }
            }
            """,
        ).shouldHaveSize(0)
    }

    test("resource cleanup followed by unconditional rethrow preserves cancellation") {
        architectureFindings(
            "NoSwallowedCancellationInSuspend", "data/src/Probe.kt",
            """
            suspend fun load() {
                try { work() } catch (error: Exception) {
                    releaseResource()
                    throw error
                }
            }
            """,
        ).shouldHaveSize(0)
    }
})

private fun architectureFindings(ruleName: String, path: String, source: String): List<Finding> {
    val rule = checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(ruleName)]) {
        "Missing production architecture rule: $ruleName"
    }.invoke(Config.empty)
    val directory = Files.createTempDirectory("lomo-architecture-evasion")
    try {
        val file = directory.resolve(path)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return rule.lint(compileForTest(file))
    } finally {
        check(directory.toFile().deleteRecursively()) { "Could not clean fixture $directory" }
    }
}
