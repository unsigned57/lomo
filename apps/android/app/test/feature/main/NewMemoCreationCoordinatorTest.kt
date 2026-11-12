package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterAnimationRegistry
import com.lomo.ui.component.common.PendingEnter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: serialize creation presentation through committed identity, exact query rank and
 * viewport acknowledgement; owning layer: app; priority: P1.
 * Scenarios:
 * - Given a deep viewport and slow write, when submitted, then scrolling starts before the commit;
 *   enter waits for scrolling and committed identity acknowledgement.
 * - Given an empty baseline, when creation commits, then the exact target still reveals.
 * - Given pinned rows, when the committed memo has a nonzero rank, then that rank is revealed.
 * - Given a filtered-out creation, when its rank is absent, then no unrelated row is revealed.
 * - Given timeout, failure or cancellation, when submission ends, then holds and the slot release.
 * - Given overlapping requests, when another submit arrives, then it cannot steal the viewport.
 * Observable outcomes: ordered presentation events, active/held identities, retry acceptance.
 * TDD proof: pinned regression RED: ui-bugs/app-red.log expected [created], got [].
 * GREEN: ui-bugs/app-green.log. Prior RED ./kotlin test --include-module=app
 * --include-classes=com.lomo.app.feature.main.NewMemoCreationCoordinatorTest failed two scenarios:
 * slow writes left atTop=false, and scroll started at event index 4 instead of 1.
 * Evidence: target/lomo/reports/manual/ui-interactions/creation-red.stdout.log.
 * GREEN: same spec plus NewMemoCreationOracleContractTest in creation-green.stdout.log.
 * Production scope: apps/android/app/src/feature/main/NewMemoCreationCoordinator.kt
 * Excludes: engine write correctness and actual Compose frame timing.
 * Test Change Justification:
 * Reason category: presentation resumes on the owning scope instead of an Unconfined continuation.
 * Old behavior/assertion being replaced: advanceUntilIdle was used to drain backgroundScope continuations.
 * Why old assertion is no longer correct: that scheduler operation stops when only background work remains;
 * the old tests passed because Unconfined bypassed the owner dispatcher after each deferred completion.
 * Coverage preserved by: every existing assertion and controlled commit/scroll/cancellation sequence;
 * runCurrent explicitly drains ready owner-scope work without advancing unrelated future timers.
 * Why this is not fitting the test to the implementation: CreationScopeAffinityTest independently rejects worker-thread UI access.

 * Reason category: requested pinned-row interaction correction.
 * Old behavior/assertion being replaced: commit and rank lookup had to precede return-to-top;
 * filtered-out commits never scrolled.
 * Why old assertion is no longer correct: pinned rows give valid creations nonzero ranks; waiting for storage produces the reported
 * pause after submitting a new memo below the top. Submission now initiates return-to-top.
 * Coverage preserved by: independent slow-write and slow-scroll scenarios, exact identity,
 * filtered-out exclusion from enter, failed submission, overlap, timeout and cancellation cases.
 * Why this is not fitting the test to the implementation: tests require immediate viewport work
 * while the write is suspended and forbid revealing before scroll/identity acknowledgement.
 * Paging overlap justification:
 * Reason category: requested creation latency correction.
 * Old behavior/assertion being replaced: a failed scroll prevented even the committed row's paging request.
 * Why old assertion is no longer correct: paging now starts while scrolling is in flight, so the
 * request can already have started when a scroll failure is observed.
 * Coverage preserved by: failure still cancels the hold, never starts entry, and allows retry;
 * NewMemoPagingOverlapTest proves paging overlaps a suspended scroll without revealing early.
 * Why this is not fitting the test to the implementation: independent paging is the required
 * latency improvement; durable success and viewport acknowledgement still govern entry.
 * TDD proof: paging-red.log records no paging while scroll waits; app-green.log verifies overlap,
 * under target/lomo/reports/manual/interaction-latency/.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationCoordinatorTest :
    FunSpec({
        test("given a slow durable write when submitting below the top then scrolling starts before the commit") {
            runTest {
                val fixture = CreationFixture(backgroundScope, atTop = false)
                val commit = CompletableDeferred<Unit>()
                fixture.beforeCommit = { commit.await() }
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.atTop shouldBe true
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                commit.complete(Unit)
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }

        test("given a suspended return to top when submitting then the write commits and enter waits for scrolling") {
            runTest {
                val fixture = CreationFixture(backgroundScope, atTop = false)
                val scroll = CompletableDeferred<Unit>()
                fixture.beforeScrollCompletes = { scroll.await() }
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.events.first() shouldBe "prepare"
                fixture.events.indexOf("scroll") shouldBe 1
                fixture.registry.enterState.value.pendingEnter shouldBe PendingEnter.AwaitingViewport(
                    fixture.request(),
                    "created",
                )
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                scroll.complete(Unit)
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }

        test("given a failed return to top when creation commits then the memo remains saved without a false reveal") {
            runTest {
                val fixture = CreationFixture(backgroundScope, atTop = false)
                fixture.beforeScrollCompletes = { error("viewport unavailable") }
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.events shouldBe listOf(
                    "prepare",
                    "scroll",
                    "create:body:false",
                    "bind:created",
                    "rank:created",
                    "await:created",
                    "cancel",
                )
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.beforeScrollCompletes = {}
                fixture.coordinator.submit("retry") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }

        test(
            "given a failed write during return to top when submission ends then scrolling is cancelled and the slot releases",
        ) {
            runTest {
                val fixture = CreationFixture(backgroundScope, atTop = false)
                val scroll = CompletableDeferred<Unit>()
                val scrollCancelled = CompletableDeferred<Unit>()
                fixture.beforeScrollCompletes = {
                    try {
                        scroll.await()
                    } finally {
                        scrollCancelled.complete(Unit)
                    }
                }
                fixture.createdId = null
                fixture.coordinator.submit("failure") shouldBe true
                runCurrent()
                scrollCancelled.isCompleted shouldBe true
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.createdId = "retry-id"
                fixture.beforeScrollCompletes = {}
                fixture.coordinator.submit("retry") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("retry-id")
            }
        }

        test(
            "given a deep viewport when scrolling overlaps creation then enter waits for exact head and viewport acknowledgement",
        ) {
            runTest {
                val fixture = CreationFixture(backgroundScope, atTop = false)
                val viewport = CompletableDeferred<Boolean>()
                fixture.viewport = { viewport.await() }
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.events shouldBe listOf(
                    "prepare",
                    "scroll",
                    "create:body:false",
                    "bind:created",
                    "rank:created",
                    "await:created",
                    "viewport:created",
                )
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.registry.enterState.value.pendingEnter shouldBe PendingEnter.AwaitingViewport(
                    fixture.request(),
                    "created",
                )
                viewport.complete(true)
                runCurrent()
                fixture.events.last() shouldBe "enter"
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }

        test(
            "given the viewport already at top when creation commits then only exact head and layout acknowledgement precede enter",
        ) {
            runTest {
                val fixture = CreationFixture(backgroundScope)
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.events shouldBe listOf(
                    "prepare",
                    "create:body:true",
                    "bind:created",
                    "rank:created",
                    "await:created",
                    "viewport:created",
                    "enter",
                )
            }
        }

        test("given an empty or unloaded baseline when creation commits then the returned identity still reveals") {
            runTest {
                val fixture = CreationFixture(backgroundScope, baselineKeys = emptySet())
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
                fixture.events.last() shouldBe "enter"
            }
        }

        test("given pinned rows before the committed memo when ranked then its own enter still runs") {
            runTest {
                val fixture = CreationFixture(backgroundScope)
                fixture.rank = 2
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
                fixture.awaitedRank shouldBe 2
                fixture.revealedRank shouldBe 2
                fixture.events.last() shouldBe "enter"
            }
        }

        test("given a filtered-out commit when ranked then return-to-top completes without revealing another row") {
            runTest {
                val fixture = CreationFixture(backgroundScope, atTop = false)
                fixture.rank = null
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.events shouldBe listOf(
                    "prepare",
                    "scroll",
                    "create:body:false",
                    "bind:created",
                    "rank:created",
                    "cancel",
                )
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
            }
        }

        test(
            "given head or viewport timeout when presentation finishes then the hold releases and a retry is accepted",
        ) {
            runTest {
                for (headMatches in listOf(false, true)) {
                    val fixture = CreationFixture(backgroundScope)
                    fixture.headMatches = headMatches
                    fixture.viewport = { false }
                    fixture.coordinator.submit("body") shouldBe true
                    runCurrent()
                    fixture.registry.enterState.value.pendingEnter shouldBe null
                    fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                    fixture.events.last() shouldBe "cancel"
                    ("viewport:created" in fixture.events) shouldBe headMatches
                    fixture.coordinator.submit("retry") shouldBe true
                    runCurrent()
                }
            }
        }

        test("given failed creation when a later retry commits then no hold or in-flight slot leaks") {
            runTest {
                val fixture = CreationFixture(backgroundScope)
                fixture.createdId = null
                fixture.coordinator.submit("failure") shouldBe true
                runCurrent()
                fixture.events shouldBe listOf("prepare", "create:failure:true", "cancel")
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.createdId = "retry-id"
                fixture.coordinator.submit("retry") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.activeEnters shouldBe setOf("retry-id")
            }
        }

        test("given a pending viewport when another submission arrives then it cannot steal the first reveal") {
            runTest {
                val fixture = CreationFixture(backgroundScope)
                val viewport = CompletableDeferred<Boolean>()
                fixture.viewport = { viewport.await() }
                fixture.coordinator.submit("first") shouldBe true
                fixture.coordinator.submit("second") shouldBe false
                viewport.complete(true)
                runCurrent()
                fixture.events.filter { it.startsWith("create:") } shouldBe listOf("create:first:true")
                fixture.registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }

        test("given cancellation while awaiting layout when a retry arrives then no stale enter survives") {
            runTest {
                val fixture = CreationFixture(backgroundScope)
                fixture.viewport = { throw kotlinx.coroutines.CancellationException("viewport disposed") }
                fixture.coordinator.submit("body") shouldBe true
                runCurrent()
                fixture.registry.enterState.value.pendingEnter shouldBe null
                fixture.registry.enterState.value.activeEnters shouldBe emptySet()
                fixture.viewport = { true }
                fixture.coordinator.submit("retry") shouldBe true
                runCurrent()
                fixture.events.last() shouldBe "enter"
            }
        }
    })

private class CreationFixture(
    scope: CoroutineScope,
    var atTop: Boolean = true,
    baselineKeys: Set<String> = setOf("old"),
) {
    val registry = EnterAnimationRegistry()
    val events = mutableListOf<String>()
    var rank: Int? = 0
    var awaitedRank: Int? = null
    var revealedRank: Int? = null
    var createdId: String? = "created"
    var headMatches = true
    var viewport: suspend () -> Boolean = { true }
    var beforeCommit: suspend () -> Unit = {}
    var beforeScrollCompletes: suspend () -> Unit = {}
    private var preparedRequest: com.lomo.ui.component.common.EnterRequestId? = null
    fun request() = checkNotNull(preparedRequest)
    val coordinator = NewMemoCreationCoordinator(
        NewMemoCreationCoordinatorDependencies<String>(
            scope = scope,
            isListAtAbsoluteTop = { atTop },
            scrollListToAbsoluteTop = {
                events += "scroll"
                beforeScrollCompletes()
                atTop = true
            },
            readBaselineKeys = { baselineKeys },
            prepareEnter = { keys ->
                events += "prepare"
                registry.beginPendingEnter(keys).also { preparedRequest = it }
            },
            bindCommittedEnter = { request, id ->
                events += "bind:$id"
                registry.bindPendingEnter(request, id)
            },
            startPreparedEnter = { request ->
                events += "enter"
                registry.startPendingEnter(request)
            },
            createMemo = { body, wasAtTop, onAccepted ->
                events += "create:$body:$wasAtTop"
                beforeCommit()
                createdId?.also(
                    onAccepted,
                )
            },
            createdMemoRank = { id ->
                events += "rank:$id"
                rank
            },
            awaitCreatedItem = { id, rank ->
                awaitedRank = rank
                events += "await:$id"
                headMatches
            },
            revealCreatedItem = { id, rank ->
                revealedRank = rank
                events += "viewport:$id"
                viewport()
            },
            cancelPreparedEnter = { request ->
                events += "cancel"
                registry.cancelEnterRequest(request)
            },
            releaseEnter = registry::settleEnter,
        ),
    )
}
