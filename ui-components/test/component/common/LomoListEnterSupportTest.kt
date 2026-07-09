package com.lomo.ui.component.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: EnterAnimationRegistry and entering resolution logic.
 * - Owning layer: ui-components/common
 * - Priority tier: P1
 * - Capability: verify state management and filter logic for enter animations via EnterAnimationRegistry.
 *
 * Scenarios:
 * - Given an empty registry, when items are queried, no item is marked entering.
 * - Given registry has an entering id, when it matches an item in the list, then that item is marked entering.
 * - Given registry has an entering id, when it does not exist in the list, then nothing is marked entering.
 * - Given a pending existing-head enter request, when a different first item appears, then that item is marked entering on the first resolved frame.
 * - Given a pending empty-list enter request, when the first item appears, then that item is marked entering on the first resolved frame.
 * - Given a pending head-enter request is canceled, when a different first item appears, then no pending enter is resolved.
 * - Given an entering id in the registry, when the enter animation is settled, then the id is removed from the registry.
 * - Given the registry is cleared, then active enters set is empty.
 *
 * Observable outcomes:
 * - returned activeEnters flow values, filtered entering ids.
 *
 * TDD proof:
 * - Fails before the fix because a nullable previous head conflates an actually empty list with an
 *   unknown unloaded head, allowing a pending create to resolve against an unproven top baseline.
 *
 * Excludes:
 * - Compose recomposition, Animatable timing, LazyColumn layout internals.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed (head-enter baseline race fix).
 * - Old behavior/assertion being replaced: tests asserted only immediate active enter ids
 *   from a set, with no pending request state or distinction between empty and unloaded head.
 * - Why old assertion is no longer correct: active enters alone cannot model a create request
 *   that must wait until the list head proves whether it is an existing item or a real empty list.
 * - Coverage preserved by: existing active enter, settle, clear, and missing-item scenarios
 *   remain covered while pending head-enter resolution and cancellation are added.
 * - Why this is not fitting the test to the implementation: the assertions encode the
 *   observable list-enter contract that only the first item after a known baseline may animate.
 */

class LomoListEnterSupportTest : FunSpec({
    test("empty registry produces empty entering ids") {
        val registry = EnterAnimationRegistry()
        val items = listOf("a", "b", "c")

        val entering = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = items,
            itemKey = { it },
        ).enteringIds

        entering.shouldContainExactly()
    }

    test("registry with active enters filters to present keys only") {
        val registry = EnterAnimationRegistry()
        registry.beginEnter("b")
        registry.beginEnter("d")

        val items = listOf("a", "b", "c")
        val entering = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = items,
            itemKey = { it },
        ).enteringIds

        entering shouldBe setOf("b")
    }

    test("pending existing-head enter resolves a different first item before active enter is promoted") {
        val registry = EnterAnimationRegistry()
        val requestId = registry.beginPendingHeadEnter(HeadEnterBaseline.ExistingHead("old-head"))

        val detection = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = listOf("new-head", "old-head", "other"),
            itemKey = { it },
        )

        detection.enteringIds shouldBe setOf("new-head")
        detection.resolvedPendingHeadEnters shouldBe mapOf(requestId to "new-head")

        registry.resolvePendingHeadEnter(requestId = requestId, headId = "new-head")
        registry.enterState.value.activeEnters shouldBe setOf("new-head")
        registry.enterState.value.pendingHeadEnters shouldBe emptyList()
    }

    test("pending existing-head enter does not resolve while the previous head is still first") {
        val registry = EnterAnimationRegistry()
        registry.beginPendingHeadEnter(HeadEnterBaseline.ExistingHead("old-head"))

        val detection = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = listOf("old-head", "other"),
            itemKey = { it },
        )

        detection.enteringIds shouldBe emptySet()
        detection.resolvedPendingHeadEnters shouldBe emptyMap()
    }

    test("pending empty-list enter resolves only after the first real head appears") {
        val registry = EnterAnimationRegistry()
        val requestId = registry.beginPendingHeadEnter(HeadEnterBaseline.EmptyList)

        val emptyDetection = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = emptyList<String>(),
            itemKey = { it },
        )

        emptyDetection.enteringIds shouldBe emptySet()
        emptyDetection.resolvedPendingHeadEnters shouldBe emptyMap()

        val firstItemDetection = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = listOf("first-head"),
            itemKey = { it },
        )

        firstItemDetection.enteringIds shouldBe setOf("first-head")
        firstItemDetection.resolvedPendingHeadEnters shouldBe mapOf(requestId to "first-head")
    }

    test("pending head enter stores an explicit baseline instead of a nullable previous head") {
        val registry = EnterAnimationRegistry()

        registry.beginPendingHeadEnter(HeadEnterBaseline.EmptyList)

        registry.enterState.value.pendingHeadEnters.single().baseline shouldBe HeadEnterBaseline.EmptyList
    }

    test("canceling pending head enter prevents later first item resolution") {
        val registry = EnterAnimationRegistry()
        val requestId = registry.beginPendingHeadEnter(HeadEnterBaseline.ExistingHead("old-head"))

        registry.cancelEnterRequest(requestId)
        val detection = resolveEnteringIds(
            enterState = registry.enterState.value,
            allItems = listOf("new-head", "old-head"),
            itemKey = { it },
        )

        detection.enteringIds shouldBe emptySet()
        detection.resolvedPendingHeadEnters shouldBe emptyMap()
    }

    test("settleEnter removes the id from registry") {
        val registry = EnterAnimationRegistry()
        registry.beginEnter("b")
        registry.enterState.value.activeEnters shouldBe setOf("b")

        registry.settleEnter("b")
        registry.enterState.value.activeEnters shouldBe emptySet<String>()
    }

    test("clear empty registry active enters") {
        val registry = EnterAnimationRegistry()
        registry.beginEnter("a")
        registry.beginEnter("b")
        registry.beginPendingHeadEnter(HeadEnterBaseline.ExistingHead("b"))
        registry.enterState.value.activeEnters shouldBe setOf("a", "b")
        registry.enterState.value.pendingHeadEnters.size shouldBe 1

        registry.clear()
        registry.enterState.value.activeEnters shouldBe emptySet<String>()
        registry.enterState.value.pendingHeadEnters shouldBe emptyList()
    }
})
