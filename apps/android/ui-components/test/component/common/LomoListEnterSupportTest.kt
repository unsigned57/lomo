package com.lomo.ui.component.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: bind creation motion to its committed identity and viewport acknowledgement;
 * owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given a pending creation, when a deep page replaces the loaded window, then loaded existing
 *   rows are neither held nor entering.
 * - Given an engine-provisional publish before commit acknowledgement, then only that provisional
 *   row stays held while already-loaded rows stay visible.
 * - Given a committed identity, then only that identity stays held until the viewport acknowledges.
 * - Given cancellation, clear or stale acknowledgement, then no unrelated row starts entering.
 * - Given an active enter absent from this window, then it never transfers to another identity.
 * Observable outcomes: held and entering identity sets and registry lifecycle state.
 * TDD proof: ./kotlin test --include-module=ui-components
 * --include-classes='com.lomo.ui.component.common.LomoListEnterSupportTest'
 * RED (a loaded top page was held during a pending creation): expected [] but was ["rank-300",
 * "rank-301"]; evidence: target/lomo/reports/manual/list-motion/enter-red-new.stdout.log.
 * GREEN: target/lomo/reports/manual/list-motion/enter-green.stdout.log.
 * Earlier identity-binding RED/GREEN: target/lomo/reports/manual/list-motion/enter-red.stdout.log.
 * Excludes: Compose layout frames and persistence.
 * Test Change Justification:
 * Reason category: product contract corrected.
 * Old behavior/assertion being replaced: any changed loaded head was immediately promoted to enter.
 * Why old assertion is no longer correct: loaded-window heads can be existing deep rows, unrelated
 * commits, or rows published before scrolling ends.
 * Coverage preserved by: empty/loaded windows, cancellation, settle, clear, and missing active
 * identities remain covered, plus commit binding and viewport gating.
 * Why this is not fitting the test to the implementation: only the created identity may animate,
 * and animation cannot begin until position acknowledgement, regardless of publication timing.
 * Test Change Justification:
 * Reason category: product contract corrected — the creation hold is scoped to provisional rows.
 * Old behavior/assertion being replaced: every row that appeared after submit and was absent from
 * the pre-submit loaded window was held for the whole pending creation.
 * Why old assertion is no longer correct: after a return-to-top the whole freshly loaded top page
 * is absent from the pre-submit window, so it was held at zero height for the entire durable write
 * and the viewport visibly blanked before the created row entered.
 * Coverage preserved by: the provisional-publication hold, the committed-identity hold, empty
 * baselines, cancellation, settle, clear and absent-identity scenarios.
 * Why this is not fitting the test to the implementation: loaded existing rows are not the row
 * under creation; the engine marks exactly that row as pending while its commit is in flight, so
 * the hold still hides the row under creation and nothing else.
 * Production scope: apps/android/ui-components/src/component/common/LomoListEnterSupport.kt
 * Production scope: apps/android/app/src/feature/main/PagedMemoListContent.kt
 */
class LomoListEnterSupportTest :
    FunSpec({
        test("given a pending creation when a deep page replaces the loaded window then loaded rows stay visible") {
            val registry = EnterAnimationRegistry()
            registry.beginPendingEnter(setOf("absolute-head"))
            val rows = listOf(TestRow("rank-300"), TestRow("rank-301"))
            val detection = resolveEnteringIds(registry.enterState.value, rows, { it.key }, { it.provisional })
            detection.enteringIds shouldBe emptySet()
            detection.heldIds shouldBe emptySet()
        }

        test(
            "given an engine-provisional publish before commit acknowledgement then only the provisional row stays held",
        ) {
            val registry = EnterAnimationRegistry()
            registry.beginPendingEnter(setOf("old-head", "other"))
            val rows =
                listOf(
                    TestRow("new", provisional = true),
                    TestRow("old-head"),
                    TestRow("other"),
                )
            val detection = resolveEnteringIds(registry.enterState.value, rows, { it.key }, { it.provisional })
            detection.enteringIds shouldBe emptySet()
            detection.heldIds shouldBe setOf("new")
        }

        test("given an unrelated head when commit binds then only the committed identity stays held") {
            val registry = EnterAnimationRegistry()
            val request = registry.beginPendingEnter(setOf("old"))
            registry.bindPendingEnter(request, "created")
            val rows = listOf(TestRow("unrelated"), TestRow("created"), TestRow("old"))
            val held = resolveEnteringIds(registry.enterState.value, rows, { it.key }, { it.provisional })
            held.heldIds shouldBe setOf("created")
            held.enteringIds shouldBe emptySet()
            registry.startPendingEnter(request)
            val detection = resolveEnteringIds(registry.enterState.value, rows, { it.key }, { it.provisional })
            detection.heldIds shouldBe emptySet()
            detection.enteringIds shouldBe setOf("created")
            registry.enterState.value.pendingEnter shouldBe null
        }

        test("given no loaded baseline when a provisional first row publishes then it stays held") {
            val registry = EnterAnimationRegistry()
            val request = registry.beginPendingEnter(emptySet())
            val empty =
                resolveEnteringIds(registry.enterState.value, emptyList<TestRow>(), { it.key }, { it.provisional })
            empty.heldIds shouldBe emptySet()
            val provisional =
                resolveEnteringIds(
                    registry.enterState.value,
                    listOf(TestRow("first", provisional = true)),
                    { it.key },
                    { it.provisional },
                )
            provisional.heldIds shouldBe setOf("first")
            registry.bindPendingEnter(request, "first")
            registry.startPendingEnter(request)
            val entering =
                resolveEnteringIds(registry.enterState.value, listOf(TestRow("first")), { it.key }, { it.provisional })
            entering.enteringIds shouldBe setOf("first")
        }

        test("given no loaded baseline when a durable first row publishes then the hold does not blind it") {
            val registry = EnterAnimationRegistry()
            registry.beginPendingEnter(emptySet())
            val detection =
                resolveEnteringIds(registry.enterState.value, listOf(TestRow("first")), { it.key }, { it.provisional })
            detection.heldIds shouldBe emptySet()
        }

        test("given a canceled request when late acknowledgements arrive then they cannot affect its replacement") {
            val registry = EnterAnimationRegistry()
            val stale = registry.beginPendingEnter(emptySet())
            registry.cancelEnterRequest(stale)
            val current = registry.beginPendingEnter(setOf("old"))
            registry.bindPendingEnter(stale, "wrong")
            registry.startPendingEnter(stale)
            registry.cancelEnterRequest(stale)
            registry.enterState.value.pendingEnter shouldBe PendingEnter.Creating(current, setOf("old"))
            registry.enterState.value.activeEnters shouldBe emptySet()
        }

        test("given an uncommitted request when viewport acknowledgement arrives then invalid ordering is rejected") {
            val registry = EnterAnimationRegistry()
            val request = registry.beginPendingEnter(emptySet())
            shouldThrow<IllegalStateException> { registry.startPendingEnter(request) }
            registry.enterState.value.activeEnters shouldBe emptySet()
        }

        test("given an active identity absent from the window when another page arrives then motion never transfers") {
            val registry = EnterAnimationRegistry()
            val request = registry.beginPendingEnter(emptySet())
            registry.bindPendingEnter(request, "created")
            registry.startPendingEnter(request)
            val detection =
                resolveEnteringIds(registry.enterState.value, listOf(TestRow("other")), { it.key }, { it.provisional })
            detection.enteringIds shouldBe emptySet()
            registry.settleEnter("created")
            registry.enterState.value.activeEnters shouldBe emptySet()
        }

        test("given active and pending entries when cleared then all holds and motions are released") {
            val registry = EnterAnimationRegistry()
            val first = registry.beginPendingEnter(emptySet())
            registry.bindPendingEnter(first, "first")
            registry.startPendingEnter(first)
            val second = registry.beginPendingEnter(setOf("first"))
            registry.clear()
            registry.bindPendingEnter(second, "second")
            registry.startPendingEnter(second)
            registry.enterState.value shouldBe EnterAnimationState()
        }
    })

private data class TestRow(val key: String, val provisional: Boolean = false)
