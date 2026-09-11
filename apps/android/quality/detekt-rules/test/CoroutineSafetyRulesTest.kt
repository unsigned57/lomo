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
 * - Unit under test: NoSwallowedCancellationInSuspendRule, NoUnboundedFlowSharingRule, NoUnmanagedCoroutineScopeRule
 * - Owning layer: quality (coroutine and reactive flow safety)
 * - Priority tier: P0
 *
 * Capability:
 * - NoSwallowedCancellationInSuspendRule: prevents generic catch clauses and runCatching from swallowing CancellationException in suspend functions.
 * - NoUnboundedFlowSharingRule: prevents SharingStarted.Lazily and Eagerly in UI layer (/app/src/).
 * - NoUnmanagedCoroutineScopeRule: prevents creating unmanaged CoroutineScope instances or using GlobalScope.
 *
 * Scenarios:
 * - Given a suspend catch that swallows CancellationException, when linted, then a finding is reported.
 * - Given SharingStarted.Lazily or Eagerly in app/src, when linted, then unbounded sharing is reported.
 * - Given GlobalScope or an unmanaged CoroutineScope, when linted, then a finding is reported.
 *
 * Observable outcomes:
 * - Registered rule presence and finding counts for compiled fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because cancellation, Lazily/Eagerly, and GlobalScope compile without findings.
 *
 * Excludes:
 * - Runtime coroutine scheduling and Android lifecycle owners.
 */
class CoroutineSafetyRulesTest : FunSpec({
    test("registers NoSwallowedCancellationInSuspend, NoUnboundedFlowSharing, and NoUnmanagedCoroutineScope in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoSwallowedCancellationInSuspend")].shouldNotBeNull()
        rules[RuleName("NoUnboundedFlowSharing")].shouldNotBeNull()
        rules[RuleName("NoUnmanagedCoroutineScope")].shouldNotBeNull()
    }

    test("NoSwallowedCancellationInSuspend: flags catch(e: Exception) in suspend function without rethrow") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    suspend fun execute() {
                        try {
                            doWork()
                        } catch (e: Exception) {
                            // swallowed cancellation
                        }
                    }
                    private suspend fun doWork() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Swallowed CancellationException"
    }

    test("NoSwallowedCancellationInSuspend: flags catch(_: Exception) wildcard catch in suspend function") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    suspend fun execute() {
                        try {
                            doWork()
                        } catch (_: Exception) {
                            // swallowed
                        }
                    }
                    private suspend fun doWork() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Swallowed CancellationException"
    }

    test("NoSwallowedCancellationInSuspend: flags unchecked runCatching in suspend function") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    suspend fun execute() {
                        runCatching {
                            doWork()
                        }
                    }
                    private suspend fun doWork() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Unchecked 'runCatching' in suspend function"
    }

    test("NoSwallowedCancellationInSuspend: allows specific non-cancellation exception catch") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase
                import java.io.IOException

                class SampleUseCase {
                    suspend fun execute() {
                        try {
                            doWork()
                        } catch (e: IOException) {
                            // Safe: IOException does not catch CancellationException
                        }
                    }
                    private suspend fun doWork() {}
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoSwallowedCancellationInSuspend: allows catch when explicitly rethrowing CancellationException") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase
                import kotlinx.coroutines.CancellationException

                class SampleUseCase {
                    suspend fun execute() {
                        try {
                            doWork()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            // handle other exceptions
                        }
                    }
                    private suspend fun doWork() {}
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoSwallowedCancellationInSuspend: allows catch with cancellation-swallowed-ok opt-out") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    suspend fun execute() {
                        try {
                            doWork()
                        } catch (e: Exception) {
                            // behavior-contract: cancellation-swallowed-ok: best-effort teardown must finish
                        }
                    }
                    private suspend fun doWork() {}
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoSwallowedCancellationInSuspend: allows catch in normal non-suspend function") {
        val findings =
            rule("NoSwallowedCancellationInSuspend").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    fun execute() {
                        try {
                            doSyncWork()
                        } catch (e: Exception) {
                            // Non-suspend function does not run coroutine cancellations
                        }
                    }
                    private fun doSyncWork() {}
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnboundedFlowSharing: flags SharingStarted.Lazily in app/src/ ViewModel") {
        val findings =
            rule("NoUnboundedFlowSharing").findingsForSource(
                "app/src/feature/main/MainViewModel.kt",
                """
                package com.lomo.app.feature.main
                import kotlinx.coroutines.flow.flowOf
                import kotlinx.coroutines.flow.stateIn
                import kotlinx.coroutines.flow.SharingStarted
                import kotlinx.coroutines.CoroutineScope

                class MainViewModel(scope: CoroutineScope) {
                    val state = flowOf(1).stateIn(scope, SharingStarted.Lazily, 0)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Forbidden SharingStarted.Lazily"
    }

    test("NoUnboundedFlowSharing: flags SharingStarted.Eagerly in app/src/ ViewModel") {
        val findings =
            rule("NoUnboundedFlowSharing").findingsForSource(
                "app/src/feature/main/MainViewModel.kt",
                """
                package com.lomo.app.feature.main
                import kotlinx.coroutines.flow.flowOf
                import kotlinx.coroutines.flow.stateIn
                import kotlinx.coroutines.flow.SharingStarted
                import kotlinx.coroutines.CoroutineScope

                class MainViewModel(scope: CoroutineScope) {
                    val state = flowOf(1).stateIn(scope, SharingStarted.Eagerly, 0)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Forbidden SharingStarted.Eagerly"
    }

    test("NoUnboundedFlowSharing: allows SharingStarted.WhileSubscribed in app/src/") {
        val findings =
            rule("NoUnboundedFlowSharing").findingsForSource(
                "app/src/feature/main/MainViewModel.kt",
                """
                package com.lomo.app.feature.main
                import kotlinx.coroutines.flow.flowOf
                import kotlinx.coroutines.flow.stateIn
                import kotlinx.coroutines.flow.SharingStarted
                import kotlinx.coroutines.CoroutineScope

                class MainViewModel(scope: CoroutineScope) {
                    val state = flowOf(1).stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnboundedFlowSharing: allows Eagerly in data/src/ service") {
        val findings =
            rule("NoUnboundedFlowSharing").findingsForSource(
                "data/src/engine/lan/RustLanShareService.kt",
                """
                package com.lomo.data.engine.lan
                import kotlinx.coroutines.flow.flowOf
                import kotlinx.coroutines.flow.stateIn
                import kotlinx.coroutines.flow.SharingStarted
                import kotlinx.coroutines.CoroutineScope

                class RustLanShareService(appScope: CoroutineScope) {
                    val deviceName = flowOf("dev").stateIn(appScope, SharingStarted.Eagerly, "init")
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnboundedFlowSharing: allows Eagerly with behavior-contract opt-out") {
        val findings =
            rule("NoUnboundedFlowSharing").findingsForSource(
                "app/src/feature/main/MainViewModel.kt",
                """
                package com.lomo.app.feature.main
                import kotlinx.coroutines.flow.flowOf
                import kotlinx.coroutines.flow.stateIn
                import kotlinx.coroutines.flow.SharingStarted
                import kotlinx.coroutines.CoroutineScope

                class MainViewModel(scope: CoroutineScope) {
                    // behavior-contract: eager-flow-ok: single-shot initialization required before composition
                    val state = flowOf(1).stateIn(scope, SharingStarted.Eagerly, 0)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnmanagedCoroutineScope: flags CoroutineScope instantiation in production source") {
        val findings =
            rule("NoUnmanagedCoroutineScope").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.Dispatchers

                class SampleUseCase {
                    val unmanagedScope = CoroutineScope(Dispatchers.IO)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Forbidden unmanaged CoroutineScope"
    }

    test("NoUnmanagedCoroutineScope: flags GlobalScope usage in production source") {
        val findings =
            rule("NoUnmanagedCoroutineScope").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase
                import kotlinx.coroutines.GlobalScope
                import kotlinx.coroutines.launch

                class SampleUseCase {
                    fun fireAndForget() {
                        GlobalScope.launch { }
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Forbidden GlobalScope usage"
    }

    test("NoUnmanagedCoroutineScope: allows CoroutineScope in DI @Provides @Singleton") {
        val findings =
            rule("NoUnmanagedCoroutineScope").findingsForSource(
                "data/src/di/EngineModule.kt",
                """
                package com.lomo.data.di
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.SupervisorJob
                import javax.inject.Singleton
                import dagger.Provides

                object EngineModule {
                    @Provides
                    @Singleton
                    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob())
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnmanagedCoroutineScope: allows CoroutineScope with unmanaged-scope-ok opt-out") {
        val findings =
            rule("NoUnmanagedCoroutineScope").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.Dispatchers

                class SampleUseCase {
                    // behavior-contract: unmanaged-scope-ok: isolated testing sandbox scope
                    val isolatedScope = CoroutineScope(Dispatchers.IO)
                }
                """,
            )

        findings.shouldHaveSize(0)
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
    val tempDir = Files.createTempDirectory("lomo-detekt-coroutine-safety-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
