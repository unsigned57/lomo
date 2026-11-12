package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterAnimationRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/*
 * Behavior Contract:
 * Capability: creation presentation returns to its owning UI scope after background rank lookup; owner: app; P1.
 * Scenarios:
 * - Given a rank lookup suspended on a worker, when it completes, then paging and viewport access run on the owner thread.
 * Observable outcomes: callback thread identity and entered committed memo identity.
 * TDD proof: target/lomo/reports/manual/four-bugs/creation-scope-red.log observes a worker callback;
 * GREEN: the same controlled worker completion returns to the owner.
 * Excludes: Android frame timing, durable storage latency and query ranking correctness.
 */
class CreationScopeAffinityTest :
    FunSpec({
        test("given rank lookup completes on a worker then paging presentation resumes on the owning scope") {
            runTest {
                val owner = Thread.currentThread()
                val rankStarted = CompletableDeferred<Unit>()
                val releaseRank = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val registry = EnterAnimationRegistry()
                var pagingOnOwner = false
                var viewportOnOwner = false
                val coordinator = NewMemoCreationCoordinator(
                    NewMemoCreationCoordinatorDependencies<String>(
                        scope = backgroundScope,
                        isListAtAbsoluteTop = { true },
                        scrollListToAbsoluteTop = { error("List is already at top") },
                        readBaselineKeys = { emptySet() },
                        prepareEnter = registry::beginPendingEnter,
                        bindCommittedEnter = registry::bindPendingEnter,
                        startPreparedEnter = { request ->
                            registry.startPendingEnter(request)
                            entered.complete(Unit)
                        },
                        createMemo = { _, _, onAccepted ->
                            onAccepted("created")
                            "created"
                        },
                        createdMemoRank = {
                            withContext(Dispatchers.Default) {
                                rankStarted.complete(Unit)
                                releaseRank.await()
                                0
                            }
                        },
                        awaitCreatedItem = { _, _ ->
                            pagingOnOwner = Thread.currentThread() === owner
                            true
                        },
                        revealCreatedItem = { _, _ ->
                            viewportOnOwner = Thread.currentThread() === owner
                            true
                        },
                        cancelPreparedEnter = registry::cancelEnterRequest,
                        releaseEnter = registry::settleEnter,
                    ),
                )
                coordinator.submit("memo") shouldBe true
                rankStarted.await()
                releaseRank.complete(Unit)
                entered.await()
                pagingOnOwner shouldBe true
                viewportOnOwner shouldBe true
                registry.enterState.value.activeEnters shouldBe setOf("created")
            }
        }
    })
