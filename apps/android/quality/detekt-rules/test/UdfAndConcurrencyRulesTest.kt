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
 * - Unit under test: ViewModelSingleStateFlowRule, NoInSituRevisionBypassRule
 * - Owning layer: quality (UDF screen-state machine & optimistic-lock guardrails, quality/udf-contract.md)
 * - Priority tier: P0
 *
 * Capability:
 * - Every app/src ViewModel belongs to exactly one UDF contract class: a screen ViewModel exposes at most one
 *   in-VM built screen-state machine StateFlow<XxxState> (combine/stateIn chain or backing asStateFlow
 *   exposure); re-exposures of dependency-owned StateFlow<XxxState> are single-sourced satellites and do not
 *   count toward machine multiplicity. Mutable* state stays private, var is reserved for Job cancellation
 *   handles; a session-facade ViewModel opts out with '// behavior-contract: session-facade-ok: <reason>'.
 *
 * Scenarios:
 * - Given a ViewModel without any screen-state machine flow, when linted, then the missing machine is reported.
 * - Given a ViewModel with exactly one machine (combine/stateIn, backing asStateFlow, or constructor-delegated),
 *   when linted, then no machine finding is produced and private Mutable inputs stay legal.
 * - Given a ViewModel exposing two machine-shaped flows, when linted, then multiplicity is reported.
 * - Given exposed Mutable* state or a var business property, when linted, then encapsulation findings cite
 *   quality/udf-contract.md and the session-facade-ok escape.
 * - Given a class-level session-facade-ok marker, when linted, then all ViewModelSingleStateFlow checks are skipped.
 * - Given a ViewModel outside /app/src/, when linted, then the rule stays silent.
 *
 * Observable outcomes:
 * - Registered rule presence, finding counts, and finding message content for app/src fixtures.
 *
 * TDD proof:
 * - Fails before ViewModelSingleStateFlow exists because a ViewModel can expose zero or many screen-state
 *   machines without a finding.
 *
 * Excludes:
 * - Type resolution across files, satellite-flow derivation policy (NoUnboundedFlowSharing), and event surfaces
 *   (SingleEventStreamRules).
 *
 * Test Change Justification:
 * Reason category: Documentation-location migration
 * Old behavior/assertion being replaced: `message shouldContain "docs/udf-contract.md"`.
 * Why old assertion is no longer correct: the contract lives in quality/ now (engineering contracts sit
 *   with quality/, not with the user-facing docs/ assets), and the rules cite the moved path.
 * Coverage preserved by: the same four assertions still require each targeted finding to cite the contract
 *   path, so a rule that stops citing the contract still fails.
 * Why this is not fitting the test to the implementation: only the cited directory changed; the rule
 *   detection logic, finding counts, and the "finding must cite the contract" guard are unchanged.
 */
class UdfAndConcurrencyRulesTest : FunSpec({
    test("registers ViewModelSingleStateFlow and NoInSituRevisionBypass in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("ViewModelSingleStateFlow")].shouldNotBeNull()
        rules[RuleName("NoInSituRevisionBypass")].shouldNotBeNull()
    }

    test("ViewModelSingleStateFlow: flags ViewModel with no screen-state machine flow") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow

                class SampleViewModel : ViewModel() {
                    private val _query = MutableStateFlow("")
                    private val _isLoading = MutableStateFlow(false)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Missing screen-state machine"
        findings.single().message shouldContain "quality/udf-contract.md"
        findings.single().message shouldContain "session-facade-ok"
    }

    test("ViewModelSingleStateFlow: allows exactly one combine/stateIn machine with private mutable inputs") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val query: String, val loading: Boolean)

                class SampleViewModel : ViewModel() {
                    private val _query = MutableStateFlow("")
                    private val _isLoading = MutableStateFlow(false)

                    val uiState: StateFlow<SampleState> =
                        combine(_query, _isLoading) { query, loading -> SampleState(query, loading) }
                            .stateIn(viewModelScope, appWhileSubscribed(), SampleState("", false))
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: allows machine exposed from private MutableStateFlow backing via asStateFlow") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState("init"))
                    val state: StateFlow<SampleState> = _state.asStateFlow()
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: allows machine delegated from a constructor dependency") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel(
                    private val coordinator: SampleCoordinator,
                ) : ViewModel() {
                    val state: StateFlow<SampleState> = coordinator.state
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: flags ViewModel exposing two in-VM built screen-state machines") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)
                data class SampleHistoryState(val entries: List<String>)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    private val _history = MutableStateFlow(SampleHistoryState(emptyList()))

                    val uiState: StateFlow<SampleState> = _state.asStateFlow()
                    val historyState: StateFlow<SampleHistoryState> = _history.asStateFlow()
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Multiple in-VM built screen-state machine flows in SampleViewModel"
        findings.single().message shouldContain "quality/udf-contract.md"
    }

    test("ViewModelSingleStateFlow: allows one built machine plus delegated re-exposures of dependency-owned state") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)
                data class SamplePrefsState(val theme: String)
                data class SampleHistoryState(val entries: List<String>)

                class SampleViewModel(
                    private val coordinator: SampleCoordinator,
                ) : ViewModel() {
                    val uiState: StateFlow<SampleState> =
                        combine(coordinator.state) { state -> state }
                            .stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))

                    val appPreferences: StateFlow<SamplePrefsState> = coordinator.prefs
                    val versionHistoryState: StateFlow<SampleHistoryState> = coordinator.historyState
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: class-level session-facade-ok marker skips all checks") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow

                // behavior-contract: session-facade-ok: wraps the recording session, no screen state machine
                class SampleViewModel : ViewModel() {
                    val state = MutableStateFlow("recording")
                    private var sessionId: String = ""
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: flags exposed MutableStateFlow, MutableState, and mutableStateOf state") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.compose.runtime.MutableState
                import androidx.compose.runtime.mutableStateOf
                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    val state = MutableStateFlow(SampleState(""))
                    val counter: MutableState<Int> = mutableStateOf(0)
                    val label = mutableStateOf("")

                    val uiState: StateFlow<SampleState> =
                        combine(state) { it }
                            .stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))
                }
                """,
            )

        findings.shouldHaveSize(3)
        findings.forEach { finding -> finding.message shouldContain "Forbidden exposed mutable state" }
        findings.forEach { finding -> finding.message shouldContain "quality/udf-contract.md" }
        findings.forEach { finding -> finding.message shouldContain "session-facade-ok" }
    }

    test("ViewModelSingleStateFlow: allows private MutableStateFlow backing") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))

                    val uiState: StateFlow<SampleState> =
                        combine(_state) { it }
                            .stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: flags var business property") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private var currentQuery: String = ""

                    val uiState: StateFlow<SampleState> =
                        combine(flows.initial) { it }
                            .stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Forbidden 'var' business property 'currentQuery'"
        findings.single().message shouldContain "quality/udf-contract.md"
    }

    test("ViewModelSingleStateFlow: allows var Job cancellation handles") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private var searchJob: Job? = null
                    private var syncJob: Job = Job()

                    val uiState: StateFlow<SampleState> =
                        combine(flows.initial) { it }
                            .stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("ViewModelSingleStateFlow: ignores ViewModel outside /app/src/") {
        val findings =
            rule("ViewModelSingleStateFlow").findingsForSource(
                "data/src/repository/SampleViewModel.kt",
                """
                package com.lomo.data.repository

                import kotlinx.coroutines.flow.MutableStateFlow

                class SampleViewModel {
                    private val _query = MutableStateFlow("")
                    private var currentQuery: String = ""
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoInSituRevisionBypass: flags reading snapshot inside mutation and piping revision") {
        val findings =
            rule("NoInSituRevisionBypass").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun delete(id: String) {
                        val snap = port.getMemo(id) ?: return
                        port.applyMemoCommand(
                            StoreMemoCommand(
                                memoId = id,
                                expectedRevision = snap.summary.contentRevision,
                            )
                        )
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Optimistic concurrency bypass (Audit U2 / TOCTOU)"
    }

    test("NoInSituRevisionBypass: allows mutation taking expectedRevision from parameter") {
        val findings =
            rule("NoInSituRevisionBypass").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun delete(id: String, expectedRevision: Long) {
                        port.applyMemoCommand(
                            StoreMemoCommand(
                                memoId = id,
                                expectedRevision = expectedRevision,
                            )
                        )
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoInSituRevisionBypass: allows in-situ read with behavior-contract comment") {
        val findings =
            rule("NoInSituRevisionBypass").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    // behavior-contract: in-situ-read-ok: emergency bypass
                    fun delete(id: String) {
                        val snap = port.getMemo(id) ?: return
                        port.applyMemoCommand(
                            StoreMemoCommand(
                                memoId = id,
                                expectedRevision = snap.summary.contentRevision,
                            )
                        )
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }
})

private fun rule(name: String): Rule =
    LomoArchitectureRuleSetProvider()
        .instance()
        .rules[RuleName(name)]
        ?.invoke(Config.empty)
        ?: error("Rule '$name' is not registered in LomoArchitectureRuleSetProvider")

private fun Rule.findingsForSource(
    relativePath: String,
    code: String,
): List<Finding> {
    val tempDir = Files.createTempDirectory("detekt-test")
    val file = tempDir.resolve(relativePath)
    file.parent?.createDirectories()
    file.writeText(code.trimIndent())
    val ktFile = compileForTest(file)
    return lint(ktFile)
}
