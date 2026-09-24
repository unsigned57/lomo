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
 * - Unit under test: NoMutableStatePayload, NoWriteInFlowDerivation, NoInferredMutableStatePayload
 * - Owning layer: quality (UDF payload immutability & read-path purity, quality/udf-contract.md)
 * - Priority tier: P0
 *
 * Capability:
 * - A type reaching the UI through a screen-state machine is an immutable snapshot: data-class *State
 *   payloads, flow-referenced *State classes and ViewModel-held state holder types never carry a var
 *   member, a mutable container, or Compose mutable state. Writes to a Mutable*Flow/Channel backing
 *   may only happen on the action path — a write hidden inside a flow-producing derivation (the
 *   chain is not terminated by a sink such as collect/launchIn) turns observation into mutation and
 *   is forbidden.
 *
 * Scenarios:
 * - Given a data class *State payload with a var/mutable container/mutableStateOf member, when
 *   linted, then the mutable member is reported.
 * - Given a non-data *State class referenced as a flow payload in-file, when linted, then mutable
 *   members are reported; without a flow reference the UI-local holder stays legal.
 * - Given a ViewModel property holding a mutable container or an in-file mutable holder type, when
 *   linted, then the exposed write capability is reported.
 * - Given a backing-flow write inside a derivation lambda whose chain is not consumed by a sink,
 *   when linted, then the read-path mutation is reported; the same write inside a sink-terminated
 *   subscription or a member function stays legal.
 * - Given a mutable-payload-ok or derivation-write-ok marker, when linted, then the finding is skipped.
 * - Given the same shapes outside /app/src/, when linted, then the rules stay silent.
 *
 * Observable outcomes:
 * - Registered rule presence, finding counts and finding message content for app/src fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because mutable payload members and derivation-path writes
 *   produce no finding.
 *
 * Excludes:
 * - Cross-file payload resolution (NoInferredMutableStatePayload, verified by the full-mode gate),
 *   event surfaces (SingleEventStreamRules) and machine multiplicity (ViewModelSingleStateFlow).
 */
class StatePayloadImmutabilityRulesTest : FunSpec({
    test("registers payload immutability and derivation-write rules in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoMutableStatePayload")].shouldNotBeNull()
        rules[RuleName("NoWriteInFlowDerivation")].shouldNotBeNull()
        rules[RuleName("NoInferredMutableStatePayload")].shouldNotBeNull()
    }

    test("NoMutableStatePayload: flags data class *State with var constructor member") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleState.kt",
                """
                package com.lomo.app.feature

                data class SampleState(var count: Int, val title: String)
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Mutable member 'count'"
        findings.single().message shouldContain "mutable-payload-ok"
        findings.single().message shouldContain "quality/udf-contract.md"
    }

    test("NoMutableStatePayload: allows immutable data class *State") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleState.kt",
                """
                package com.lomo.app.feature

                data class SampleState(val count: Int, val title: String)
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoMutableStatePayload: flags mutable container and Compose mutable state members") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleState.kt",
                """
                package com.lomo.app.feature

                data class SampleState(
                    val items: MutableList<String>,
                    val seen: MutableMap<String, Boolean>,
                ) {
                    val pending = mutableListOf<String>()
                    var label by mutableStateOf("")
                }
                """,
            )

        findings.shouldHaveSize(4)
    }

    test("NoMutableStatePayload: allows non-data *State UI-local holder without flow reference") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/DialogState.kt",
                """
                package com.lomo.app.feature

                class SampleDialogState {
                    var route by mutableStateOf<String?>(null)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoMutableStatePayload: flags non-data *State class referenced as flow payload") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                class SampleState {
                    var label by mutableStateOf("")
                }

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState())
                    val uiState: StateFlow<SampleState> = _state.asStateFlow()
                }
                """,
            )

        findings.shouldHaveSize(2)
        findings.any { finding -> finding.message.contains("Mutable member 'label'") } shouldBe true
        findings.any { finding -> finding.message.contains("Mutable holder surface 'uiState'") } shouldBe true
    }

    test("NoMutableStatePayload: flags sealed *State payload whose nested data class carries a var") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleState.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.StateFlow

                sealed interface SampleState {
                    data class Active(var retries: Int) : SampleState
                    data object Idle : SampleState
                }

                val uiState: StateFlow<SampleState> = machine.state
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "retries"
    }

    test("NoMutableStatePayload: flags payload whose member type carries a var (in-file recursion)") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleState.kt",
                """
                package com.lomo.app.feature

                data class SampleState(val detail: Detail)

                data class Detail(var note: String)
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "note"
    }

    test("NoMutableStatePayload: flags ViewModel exposing mutable container and mutable holder") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class LocalHolder {
                    var dirty = false
                }

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    val uiState: StateFlow<SampleState> = _state.asStateFlow()

                    val pendingEdits = mutableListOf<String>()
                    val holder = LocalHolder()
                }
                """,
            )

        findings.shouldHaveSize(2)
    }

    test("NoMutableStatePayload: mutable-payload-ok marker suppresses the finding") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "app/src/feature/SampleState.kt",
                """
                package com.lomo.app.feature

                // behavior-contract: mutable-payload-ok: platform cursor contract requires in-place mutation
                data class SampleState(var cursor: Int)
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoMutableStatePayload: ignores mutable payloads outside /app/src/") {
        val findings =
            rule("NoMutableStatePayload").findingsForSource(
                "data/src/model/SampleState.kt",
                """
                package com.lomo.data.model

                data class SampleState(var count: Int)
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteInFlowDerivation: flags backing write inside a machine derivation chain") {
        val findings =
            rule("NoWriteInFlowDerivation").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    private val _side = MutableStateFlow(0)

                    val uiState: StateFlow<SampleState> =
                        combine(_side, _side) { a, b ->
                            _state.value = SampleState("from derivation")
                            SampleState("${'$'}a${'$'}b")
                        }.stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "derivation-write-ok"
        findings.single().message shouldContain "quality/udf-contract.md"
    }

    test("NoWriteInFlowDerivation: allows backing write inside a sink-terminated subscription") {
        val findings =
            rule("NoWriteInFlowDerivation").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel(private val port: Any) : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    val uiState: StateFlow<SampleState> = _state.asStateFlow()

                    init {
                        port.updates
                            .onEach { next -> _state.update { it.copy(title = next) } }
                            .launchIn(viewModelScope)
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteInFlowDerivation: allows backing writes inside member functions and launch bodies") {
        val findings =
            rule("NoWriteInFlowDerivation").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    val uiState: StateFlow<SampleState> = _state.asStateFlow()

                    fun rename(title: String) {
                        viewModelScope.launch { _state.value = SampleState(title) }
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteInFlowDerivation: flags update and emit inside a flow-producing expression") {
        val findings =
            rule("NoWriteInFlowDerivation").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    private val _events = MutableSharedFlow<Int>()
                    val uiState: StateFlow<SampleState> = _state.asStateFlow()

                    val doubled = _events.map { next ->
                        _state.update { it.copy(title = "seen") }
                        _events.emit(next)
                        next * 2
                    }
                }
                """,
            )

        findings.shouldHaveSize(2)
    }

    test("NoWriteInFlowDerivation: derivation-write-ok marker suppresses the finding") {
        val findings =
            rule("NoWriteInFlowDerivation").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleState(val title: String)

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow(SampleState(""))
                    private val _side = MutableStateFlow(0)

                    // behavior-contract: derivation-write-ok: platform callback contract requires inline latch
                    val uiState: StateFlow<SampleState> =
                        _side.map { next ->
                            _state.value = SampleState("latched")
                            SampleState("seen ${'$'}next")
                        }.stateIn(viewModelScope, appWhileSubscribed(), SampleState(""))
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
