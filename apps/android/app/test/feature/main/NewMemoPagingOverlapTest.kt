package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterAnimationRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: creation paging overlaps return-to-top; owner: app; P1.
 * Scenarios:
 * - Given a committed memo and an unfinished scroll, when its query rank is known,
 *   then its paging request starts immediately while entry still waits for the viewport.
 * Observable outcomes: paging started before scrolling completed; exact committed identity
 *   enters only after both paging and viewport acknowledgement.
 * TDD proof: RED paging-red.log (pagingStarted=false); GREEN app-green.log,
 * under target/lomo/reports/manual/interaction-latency/.
 * Production scope: apps/android/app/src/feature/main/NewMemoCreationCoordinator.kt
 * Excludes: Android scrolling duration and durable transaction correctness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoPagingOverlapTest :
    FunSpec({
        test("given a committed memo during return to top then paging begins without waiting for scrolling") {
            runTest {
                val scroll = CompletableDeferred<Unit>()
                val page = CompletableDeferred<Boolean>()
                val registry = EnterAnimationRegistry()
                var pagingStarted = false
                var viewportReached = false
                val coordinator = NewMemoCreationCoordinator(
                    NewMemoCreationCoordinatorDependencies<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { false },
                        scrollListToAbsoluteTop = { scroll.await() },
                        readBaselineKeys = { setOf("existing") },
                        prepareEnter = registry::beginPendingEnter,
                        bindCommittedEnter = registry::bindPendingEnter,
                        startPreparedEnter = registry::startPendingEnter,
                        createMemo = { _, _, onAccepted ->
                            onAccepted("created")
                            "created"
                        },
                        createdMemoRank = { 3 },
                        awaitCreatedItem = { id, rank ->
                            id shouldBe "created"
                            rank shouldBe 3
                            pagingStarted = true
                            page.await()
                        },
                        revealCreatedItem = { _, _ ->
                            viewportReached = true
                            true
                        },
                        cancelPreparedEnter = registry::cancelEnterRequest,
                        releaseEnter = registry::settleEnter,
                    ),
                )
                coordinator.submit("body") shouldBe true
                runCurrent()
                pagingStarted shouldBe true
                page.complete(true)
                runCurrent()
                viewportReached shouldBe false
                registry.enterState.value.activeEnters shouldBe emptySet()
                scroll.complete(Unit)
                runCurrent()
                viewportReached shouldBe true
                registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }
    })
