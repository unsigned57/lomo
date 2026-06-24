package com.lomo.ui.component.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableSet

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
 * - Given an entering id in the registry, when the enter animation is settled, then the id is removed from the registry.
 * - Given the registry is cleared, then active enters set is empty.
 *
 * Observable outcomes:
 * - returned activeEnters flow values, filtered entering ids.
 *
 * TDD proof:
 * - Fails initially due to compiler/type errors because LomoListEnterSupport is not yet updated to the new registry-based API.
 *
 * Excludes:
 * - Compose recomposition, Animatable timing, LazyColumn layout internals.
 */

class LomoListEnterSupportTest : FunSpec({
    test("empty registry produces empty entering ids") {
        val registry = EnterAnimationRegistry()
        val items = listOf("a", "b", "c")

        val entering = resolveEnteringIds(
            activeEnters = registry.activeEnters.value,
            allItems = items,
            itemKey = { it },
        )

        entering.shouldContainExactly()
    }

    test("registry with active enters filters to present keys only") {
        val registry = EnterAnimationRegistry()
        registry.beginEnter("b")
        registry.beginEnter("d")

        val items = listOf("a", "b", "c")
        val entering = resolveEnteringIds(
            activeEnters = registry.activeEnters.value,
            allItems = items,
            itemKey = { it },
        )

        entering shouldBe setOf("b")
    }

    test("settleEnter removes the id from registry") {
        val registry = EnterAnimationRegistry()
        registry.beginEnter("b")
        registry.activeEnters.value shouldBe setOf("b")

        registry.settleEnter("b")
        registry.activeEnters.value shouldBe emptySet<String>()
    }

    test("clear empty registry active enters") {
        val registry = EnterAnimationRegistry()
        registry.beginEnter("a")
        registry.beginEnter("b")
        registry.activeEnters.value shouldBe setOf("a", "b")

        registry.clear()
        registry.activeEnters.value shouldBe emptySet<String>()
    }
})
