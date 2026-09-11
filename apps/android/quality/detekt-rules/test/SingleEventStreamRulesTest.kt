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
 * - Unit under test: NoEventInStateFlowRule, NoMultipleEffectChannelsRule
 * - Owning layer: quality (acknowledged one-shot event queue guardrails, docs/udf-contract.md)
 * - Priority tier: P0
 *
 * Capability:
 * - One-shot UI events may only flow through the acknowledged queue pattern
 *   (PendingUiEvent<T> + consume-by-id via an event-queue coordinator). A ViewModel must not park
 *   Event/Effect/Request payloads in StateFlow, and must not own any parallel Channel/MutableSharedFlow
 *   effect surface: the acknowledged queue is the only legal one-shot surface.
 *
 * Scenarios:
 * - Given a ViewModel StateFlow with an Event/Effect/Request payload, when linted, then the finding cites
 *   docs/udf-contract.md and the acknowledged queue.
 * - Given a queue-shaped StateFlow<List<PendingUiEvent<...>>> or a delegation to an event-queue coordinator,
 *   when linted, then the property is legal.
 * - Given a state-event-ok marker, when linted, then the property is legal.
 * - Given any Channel<...> or MutableSharedFlow<...> property in a ViewModel (even one), when linted, then
 *   the finding cites docs/udf-contract.md; multiple-channels-ok opts out.
 * - Given a non-ViewModel class, when linted, then neither rule reports.
 *
 * Observable outcomes:
 * - Registered rule presence, finding counts, and finding message content for app/src fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because Event payloads in StateFlow and extra Channel surfaces compile
 *   without findings.
 *
 * Excludes:
 * - Event-queue coordinator internals (data/test classes) and navigation-level event dispatch.
 */
class SingleEventStreamRulesTest : FunSpec({
    test("registers NoEventInStateFlow and NoMultipleEffectChannels in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoEventInStateFlow")].shouldNotBeNull()
        rules[RuleName("NoMultipleEffectChannels")].shouldNotBeNull()
    }

    test("NoEventInStateFlow: flags ViewModel StateFlow with Event payload") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow

                data class UserNoticeEvent(val msg: String)

                class SampleViewModel {
                    private val _noticeEvent = MutableStateFlow<UserNoticeEvent?>(null)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "'_noticeEvent'"
        findings.single().message shouldContain "docs/udf-contract.md"
        findings.single().message shouldContain "PendingUiEvent"
    }

    test("NoEventInStateFlow: flags ViewModel StateFlow with Request payload") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow

                data class CreationRequest(val id: String)

                class SampleViewModel {
                    private val _req = MutableStateFlow<CreationRequest?>(null)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "docs/udf-contract.md"
    }

    test("NoEventInStateFlow: allows StateFlow with normal state payload") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow

                data class SampleUiState(val title: String)

                class SampleViewModel {
                    private val _uiState = MutableStateFlow(SampleUiState("title"))
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoEventInStateFlow: allows acknowledged queue shape StateFlow<List<PendingUiEvent<...>>>") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class PendingUiEvent<T>(val id: Long, val payload: T)
                data class SampleAction(val name: String)

                class SampleViewModel {
                    val appActionEvents: StateFlow<List<PendingUiEvent<SampleAction>>> =
                        MutableStateFlow(emptyList())
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoEventInStateFlow: allows delegation to an event-queue coordinator member") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                data class SampleRequest(val id: Long)

                class SampleEventQueueCoordinator<T> {
                    val pending: StateFlow<T?> = MutableStateFlow(null)
                }

                class SampleViewModel {
                    private val requestQueue = SampleEventQueueCoordinator<SampleRequest>()
                    val pendingRequest: StateFlow<SampleRequest?> = requestQueue.pending
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoEventInStateFlow: allows state-event-ok opt-out") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow

                data class UserNoticeEvent(val msg: String)

                class SampleViewModel {
                    // behavior-contract: state-event-ok: special sticky event
                    private val _noticeEvent = MutableStateFlow<UserNoticeEvent?>(null)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoEventInStateFlow: ignores non-ViewModel classes") {
        val findings =
            rule("NoEventInStateFlow").findingsForSource(
                "app/src/feature/SampleStore.kt",
                """
                package com.lomo.app.feature

                import kotlinx.coroutines.flow.MutableStateFlow

                data class UserEvent(val msg: String)

                class SampleStore {
                    private val _event = MutableStateFlow<UserEvent?>(null)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoMultipleEffectChannels: flags a single Channel property in a ViewModel") {
        val findings =
            rule("NoMultipleEffectChannels").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.channels.Channel

                sealed interface SampleEffect

                class SampleViewModel : ViewModel() {
                    private val _effects = Channel<SampleEffect>(Channel.BUFFERED)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "'_effects'"
        findings.single().message shouldContain "docs/udf-contract.md"
        findings.single().message shouldContain "multiple-channels-ok"
    }

    test("NoMultipleEffectChannels: flags MutableSharedFlow property in a ViewModel") {
        val findings =
            rule("NoMultipleEffectChannels").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableSharedFlow

                data class RecoveryDiagnosticReport(val reason: String)

                class SampleViewModel : ViewModel() {
                    private val _diagnosticExports =
                        MutableSharedFlow<RecoveryDiagnosticReport>(extraBufferCapacity = 1)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "'_diagnosticExports'"
        findings.single().message shouldContain "MutableSharedFlow"
        findings.single().message shouldContain "docs/udf-contract.md"
    }

    test("NoMultipleEffectChannels: flags each parallel effect surface separately") {
        val findings =
            rule("NoMultipleEffectChannels").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.channels.Channel
                import kotlinx.coroutines.flow.MutableSharedFlow

                sealed interface SampleEffect
                data class SampleSignal(val reason: String)

                class SampleViewModel : ViewModel() {
                    private val _toastChannel = Channel<String>()
                    private val _navChannel = Channel<String>()
                    private val _signals = MutableSharedFlow<SampleSignal>(extraBufferCapacity = 1)
                }
                """,
            )

        findings.shouldHaveSize(3)
    }

    test("NoMultipleEffectChannels: allows ViewModel without parallel effect surfaces") {
        val findings =
            rule("NoMultipleEffectChannels").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow

                class SampleViewModel : ViewModel() {
                    private val _state = MutableStateFlow("init")
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoMultipleEffectChannels: allows multiple-channels-ok opt-out") {
        val findings =
            rule("NoMultipleEffectChannels").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.channels.Channel

                sealed interface SampleEffect

                class SampleViewModel : ViewModel() {
                    // behavior-contract: multiple-channels-ok: platform recording session surface
                    private val _effects = Channel<SampleEffect>(Channel.BUFFERED)
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
