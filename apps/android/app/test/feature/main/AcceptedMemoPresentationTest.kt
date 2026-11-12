package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterAnimationRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: reveal an engine-accepted memo before durable I/O settles; owner: app; P1.
 * Scenarios:
 * - Given acceptance and a suspended write, when the viewport is ready, then entry starts immediately;
 *   a second submission remains excluded until durability settles.
 * - Given a started entry, when the write fails or the owner is cancelled, then no entry survives.
 * - Given a suspended viewport, when the write fails, then that reveal is cancelled and a retry works.
 * Observable outcomes: active/held memo identities, viewport cancellation and admission of retries.
 * TDD proof: acceptance-red.log and acceptance-green.log under target/lomo/reports/manual/instant-feed/.
 * Production scope: apps/android/app/src/feature/main/NewMemoCreationCoordinator.kt
 * Excludes: Rust transaction correctness, JNI and physical frame timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptedMemoPresentationTest :
    FunSpec({
        test("given acceptance while storage waits then entry starts and the submission slot stays held") {
            runTest {
                val fixture = AcceptedPresentationFixture(backgroundScope)
                fixture.coordinator.submit("first") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("accepted")
                fixture.coordinator.submit("overlap") shouldBe false
                fixture.write.complete("accepted")
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("accepted")
                fixture.coordinator.submit("next") shouldBe true
            }
        }

        test("given entry has started when durability fails then the withdrawn identity is released") {
            runTest {
                val fixture = AcceptedPresentationFixture(backgroundScope)
                fixture.coordinator.submit("first")
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("accepted")
                fixture.write.complete(null)
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.coordinator.submit("retry") shouldBe true
            }
        }

        test("given entry has started when its presentation owner is cancelled then entry is released") {
            runTest {
                val owner =
                    CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                val fixture = AcceptedPresentationFixture(owner)
                fixture.coordinator.submit("first")
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("accepted")
                owner.cancel()
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.registry.enterState.value.pendingEnter shouldBe null
            }
        }

        test("given a pending viewport when durability fails then its work is cancelled and a retry is admitted") {
            runTest {
                val fixture = AcceptedPresentationFixture(backgroundScope)
                val viewport = CompletableDeferred<Unit>()
                var cancelled = false
                fixture.viewport = {
                    try {
                        viewport.await()
                        true
                    } finally {
                        cancelled = true
                    }
                }
                fixture.coordinator.submit("first")
                runCurrent()
                fixture.write.complete(null)
                runCurrent()
                cancelled shouldBe true
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.coordinator.submit("retry") shouldBe true
            }
        }
    })

private class AcceptedPresentationFixture(scope: CoroutineScope) {
    val registry = EnterAnimationRegistry()
    val write = CompletableDeferred<String?>()
    var viewport: suspend () -> Boolean = { true }
    val coordinator = NewMemoCreationCoordinator(
        NewMemoCreationCoordinatorDependencies<String>(
            scope = scope,
            isListAtAbsoluteTop = { true },
            scrollListToAbsoluteTop = { error("Already at top") },
            readBaselineKeys = { emptySet() },
            prepareEnter = registry::beginPendingEnter,
            bindCommittedEnter = registry::bindPendingEnter,
            startPreparedEnter = registry::startPendingEnter,
            createMemo = { _, _, accepted ->
                accepted("accepted")
                write.await()
            },
            createdMemoRank = { 0 },
            awaitCreatedItem = { _, _ -> true },
            revealCreatedItem = { _, _ -> viewport() },
            cancelPreparedEnter = registry::cancelEnterRequest,
            releaseEnter = registry::settleEnter,
        ),
    )
}
