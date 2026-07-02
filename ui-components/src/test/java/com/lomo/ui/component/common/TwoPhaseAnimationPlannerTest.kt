package com.lomo.ui.component.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: TwoPhaseAnimationPlanner and TwoPhaseSettlePolicy.
 * - Owning layer: ui-components/common.
 * - Priority tier: P1.
 * - Capability: own the two-phase sequential ordering (expand-then-fade for enter,
 *   fade-then-collapse for exit) and the settle-once / dispose-settle / rollback-reset
 *   contract, decoupled from any SubcomposeLayout so the LazyColumn slot-reuse contract
 *   is never violated by nested reuse groups.
 *
 * Scenarios:
 * - Given ENTER direction, when the plan is computed, then HEIGHT_FRACTION step precedes ALPHA step.
 * - Given EXIT direction, when the plan is computed, then ALPHA step precedes HEIGHT_FRACTION step.
 * - Given any direction, when the plan is computed, then exactly one step is final and it is the last.
 * - Given ENTER direction, when the plan is computed, then both targets are 1f (expand and reveal).
 * - Given EXIT direction, when the plan is computed, then both targets are 0f (fade and collapse).
 * - Given a started cycle, when markSettled is called twice, then onSettled fires exactly once.
 * - Given a started but unsettled cycle, when markDisposed(isActive=true), then onSettled fires exactly once.
 * - Given an already settled cycle, when markDisposed(isActive=true), then onSettled does not fire.
 * - Given a started but unsettled cycle, when markDisposed(isActive=false), then onSettled does not fire.
 * - Given a started cycle, when markRollback is called, then onSettled does not fire and isSettled is false.
 * - Given a rolled-back cycle, when a new cycle starts and settles, then onSettled fires for the new cycle.
 * - Given ENTER direction, when startValue is queried, then it returns 0f (pre-enter: invisible/collapsed).
 * - Given EXIT direction, when startValue is queried, then it returns 1f (pre-exit: visible/expanded).
 * - Given a row is entering and exiting in the same lazy item slot, when the active direction is
 *   resolved, then exactly one direction is selected and EXIT wins.
 *
 * Observable outcomes:
 * - ordered list of steps with property, targetValue, and isFinal flags.
 * - number of times the onSettled callback is invoked per lifecycle transition.
 * - isSettled flag value after each transition.
 * - startValue for each direction.
 * - active direction for the row-level visual state machine.
 *
 * TDD proof:
 * - RED: Fails to compile before TwoPhaseAnimationPlanner.startValue is introduced,
 *   proving the start-value owner does not exist yet — the Composable had no way to
 *   reset Animatable values when isEntering transitioned from false to true after
 *   first composition (the scroll-to-top race).
 * - RED: Fails to compile before TwoPhaseAnimationPlanner.activeDirection is introduced,
 *   proving enter and exit are still modeled as independent wrappers instead of one
 *   lazy-item visual lifecycle.
 *
 * Excludes:
 * - Composable rendering, Animatable timing, LazyColumn layout internals, SubcomposeLayout behavior.
 */

class TwoPhaseAnimationPlannerTest : FunSpec({
    test("given ENTER direction when plan is computed then HEIGHT_FRACTION precedes ALPHA") {
        val steps = TwoPhaseAnimationPlanner.plan(TwoPhaseDirection.ENTER)

        steps.map { it.property } shouldContainExactly
            listOf(TwoPhaseProperty.HEIGHT_FRACTION, TwoPhaseProperty.ALPHA)
    }

    test("given EXIT direction when plan is computed then ALPHA precedes HEIGHT_FRACTION") {
        val steps = TwoPhaseAnimationPlanner.plan(TwoPhaseDirection.EXIT)

        steps.map { it.property } shouldContainExactly
            listOf(TwoPhaseProperty.ALPHA, TwoPhaseProperty.HEIGHT_FRACTION)
    }

    test("given any direction when plan is computed then exactly one final step is last") {
        val enter = TwoPhaseAnimationPlanner.plan(TwoPhaseDirection.ENTER)
        val exit = TwoPhaseAnimationPlanner.plan(TwoPhaseDirection.EXIT)

        enter.filter { it.isFinal } shouldHaveSize 1
        exit.filter { it.isFinal } shouldHaveSize 1
        enter.last().isFinal shouldBe true
        exit.last().isFinal shouldBe true
    }

    test("given ENTER direction when plan is computed then both targets expand to 1f") {
        val steps = TwoPhaseAnimationPlanner.plan(TwoPhaseDirection.ENTER)

        steps.map { it.targetValue } shouldContainExactly listOf(1f, 1f)
    }

    test("given EXIT direction when plan is computed then both targets collapse to 0f") {
        val steps = TwoPhaseAnimationPlanner.plan(TwoPhaseDirection.EXIT)

        steps.map { it.targetValue } shouldContainExactly listOf(0f, 0f)
    }

    test("given a started cycle when markSettled is called twice then onSettled fires exactly once") {
        val policy = TwoPhaseSettlePolicy()
        var invokeCount = 0
        policy.markStarted()

        policy.markSettled { invokeCount++ }
        policy.markSettled { invokeCount++ }

        invokeCount shouldBe 1
        policy.isSettled shouldBe true
    }

    test("given a started unsettled cycle when markDisposed with isActive=true then onSettled fires once") {
        val policy = TwoPhaseSettlePolicy()
        var invokeCount = 0
        policy.markStarted()

        policy.markDisposed(isActive = true) { invokeCount++ }

        invokeCount shouldBe 1
        policy.isSettled shouldBe true
    }

    test("given an already settled cycle when markDisposed with isActive=true then onSettled does not fire") {
        val policy = TwoPhaseSettlePolicy()
        var invokeCount = 0
        policy.markStarted()
        policy.markSettled { invokeCount++ }

        policy.markDisposed(isActive = true) { invokeCount++ }

        invokeCount shouldBe 1
        policy.isSettled shouldBe true
    }

    test("given a started unsettled cycle when markDisposed with isActive=false then onSettled does not fire") {
        val policy = TwoPhaseSettlePolicy()
        var invokeCount = 0
        policy.markStarted()

        policy.markDisposed(isActive = false) { invokeCount++ }

        invokeCount shouldBe 0
        policy.isSettled shouldBe false
    }

    test("given a started cycle when markRollback then onSettled does not fire and isSettled is false") {
        val policy = TwoPhaseSettlePolicy()
        var invokeCount = 0
        policy.markStarted()

        policy.markRollback()

        invokeCount shouldBe 0
        policy.isSettled shouldBe false
    }

    test("given a rolled-back cycle when a new cycle starts and settles then onSettled fires for the new cycle") {
        val policy = TwoPhaseSettlePolicy()
        var invokeCount = 0
        policy.markStarted()
        policy.markRollback()

        policy.markStarted()
        policy.markSettled { invokeCount++ }

        invokeCount shouldBe 1
        policy.isSettled shouldBe true
    }

    test("given ENTER direction when startValue is queried then it returns 0f") {
        TwoPhaseAnimationPlanner.startValue(TwoPhaseDirection.ENTER) shouldBe 0f
    }

    test("given EXIT direction when startValue is queried then it returns 1f") {
        TwoPhaseAnimationPlanner.startValue(TwoPhaseDirection.EXIT) shouldBe 1f
    }

    test("given enter and exit flags when active direction is resolved then exactly one direction owns the row") {
        TwoPhaseAnimationPlanner.activeDirection(isEntering = false, isExiting = false) shouldBe null
        TwoPhaseAnimationPlanner.activeDirection(isEntering = true, isExiting = false) shouldBe
            TwoPhaseDirection.ENTER
        TwoPhaseAnimationPlanner.activeDirection(isEntering = false, isExiting = true) shouldBe
            TwoPhaseDirection.EXIT
        TwoPhaseAnimationPlanner.activeDirection(isEntering = true, isExiting = true) shouldBe
            TwoPhaseDirection.EXIT
    }
})
