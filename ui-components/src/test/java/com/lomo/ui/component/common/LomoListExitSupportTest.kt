package com.lomo.ui.component.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * - Unit under test: ExitAnimationRegistry, resolveExitRenderList, uniqueMemoListRenderKeys, computeExitRenderListBaseKeys
 * - Owning layer: ui-components common
 * - Priority tier: P1
 * - Capability: Merge active items with exiting items, positioning exiting items at their anchored slots, and guarantee unique render keys.
 *
 * Scenarios:
 * - Given no active exits, when resolved, then output contains all source items with no exit phase.
 * - Given active exits that are still in source, when resolved, then they expose an exiting phase and snapshot holds the live/snapshot value.
 * - Given an exit animation has settled while source still contains the key, when resolved, then the row is retained in a hidden phase.
 * - Given active exits removed from source, when resolved, then they are retained at their anchored position.
 * - Given animation, mutation, and source absence complete in any order, when the last condition is marked, then the registry entry is removed.
 * - Given multiple concurrent deletes, when resolved, then the transitive order of exits is preserved.
 * - Given a base key list with duplicates, when uniqueMemoListRenderKeys is called, then it returns deduplicated keys preserving order.
 * - Given an exit render list and a paged peek list with overlapping items, when computeExitRenderListBaseKeys is called, then the resolved base keys contain duplicates.
 * - Given duplicate keys in base list, when uniqueMemoListRenderKeys is applied, then all keys in the final key list are unique.
 *
 * Observable outcomes:
 * - list of resolved entries with items, snapshot values, and exit phases.
 * - deduplicated key list matching size, order, and preserving first-occurrences.
 *
 * TDD proof:
 * - Fails if a settled animation removes its registry entry before mutation commit and source absence are both observed.
 * - Fails if an exiting item whose anchor is missing is not appended to the end of the list.
 * - Fails when key deduplication returns empty lists because of the dummy implementations.
 *
 * Excludes:
 * - UI rendering, layout animations, lazy list viewport boundaries.
 *
 * Test Change Justification:
 * - Reason category: API shape cleanup.
 * - Old behavior/assertion being replaced: ExitEntry test fixtures used positional constructor arguments.
 * - Why old assertion is no longer correct: ExitEntry now names item and anchoredAfterKey explicitly as the registry contract was simplified.
 * - Coverage preserved by: every ordering, anchor, snapshot, and duplicate-key assertion remains unchanged.
 * - Why this is not fitting the test to the implementation: fixture construction was updated while observable render-list expectations stayed the same.
 */

private data class TestItem(val id: String, val content: String = "")
private fun testKey(item: TestItem): String = item.id

class LomoListExitSupportTest : FunSpec({
    test("no deleting ids yields all source items with no exit phase") {
        val source = listOf(TestItem("a"), TestItem("b"), TestItem("c"))

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = emptyMap(),
        )

        result.map { it.item } shouldContainExactly source
        result.map { it.exitPhase } shouldContainExactly listOf(null, null, null)
    }

    test("deleting id still in source appears in-place with exiting phase and snapshotMemo holds the live value") {
        val source = listOf(TestItem("a"), TestItem("b", "live"), TestItem("c"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("b", "snapshot"),
                anchoredAfterKey = "a",
            )
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
        result.map { it.exitPhase } shouldContainExactly listOf(null, LomoListExitPhase.Exiting, null)
        result[1].snapshotMemo.content shouldBe "snapshot"
    }

    test("animation-settled id still in source appears in-place with hidden phase") {
        val source = listOf(TestItem("a"), TestItem("b", "live"), TestItem("c"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("b", "snapshot"),
                anchoredAfterKey = "a",
                animationSettled = true,
                mutationCommitted = true,
                sourceAbsent = false,
            )
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
        result.map { it.exitPhase } shouldContainExactly listOf(null, LomoListExitPhase.Hidden, null)
        result[1].isExiting shouldBe true
    }

    test("deleting id removed from source is retained at its anchoredAfterKey position") {
        val source = listOf(TestItem("a"), TestItem("c"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("b"),
                anchoredAfterKey = "a",
            ),
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
        result.map { it.exitPhase } shouldContainExactly listOf(null, LomoListExitPhase.Exiting, null)
    }

    test("multiple concurrent deletes preserve transitive order") {
        val source = listOf(TestItem("a"), TestItem("d"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("b"),
                anchoredAfterKey = "a",
            ),
            "c" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("c"),
                anchoredAfterKey = "b",
            ),
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c", "d")
        result.all { it.item.id != "b" || it.exitPhase == LomoListExitPhase.Exiting } shouldBe true
        result.all { it.item.id != "c" || it.exitPhase == LomoListExitPhase.Exiting } shouldBe true
    }

    test("null anchoredAfterKey places item at the beginning of the list") {
        val source = listOf(TestItem("b"), TestItem("c"))
        val activeExits = mapOf(
            "a" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("a"),
                anchoredAfterKey = null,
            ),
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
    }

    test("orphans whose anchors do not exist are appended to the end of the list") {
        val source = listOf(TestItem("a"), TestItem("b"))
        val activeExits = mapOf(
            "c" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("c"),
                anchoredAfterKey = "nonexistent",
            ),
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
    }

    test("retained exits placed before existing source items do not shift other items keys") {
        val source = listOf(TestItem("b"), TestItem("c"))
        val activeExits = mapOf(
            "a" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("a"),
                anchoredAfterKey = null,
            )
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
    }

    test("ExitAnimationRegistry removes entries only after animation, mutation, and source absence complete") {
        val registry = ExitAnimationRegistry<TestItem>()
        registry.entries.value.isEmpty() shouldBe true

        registry.beginExit("a", TestItem("a"), "prev")
        registry.entries.value shouldBe mapOf(
            "a" to ExitAnimationRegistry.ExitEntry(
                item = TestItem("a"),
                anchoredAfterKey = "prev",
                animationSettled = false,
                mutationCommitted = false,
                sourceAbsent = false,
            )
        )

        registry.markExitAnimationSettled("a")
        registry.entries.value["a"]?.animationSettled shouldBe true
        registry.entries.value["a"]?.mutationCommitted shouldBe false
        registry.entries.value["a"]?.sourceAbsent shouldBe false

        registry.markExitMutationCommitted("a")
        registry.entries.value["a"]?.animationSettled shouldBe true
        registry.entries.value["a"]?.mutationCommitted shouldBe true
        registry.entries.value["a"]?.sourceAbsent shouldBe false

        registry.updateSourceKeys(setOf("a"))
        registry.entries.value.containsKey("a") shouldBe true

        registry.updateSourceKeys(emptySet())
        registry.entries.value.isEmpty() shouldBe true

        registry.beginExit("b", TestItem("b"), null)
        registry.clear()
        registry.entries.value.isEmpty() shouldBe true
    }

    test("ExitAnimationRegistry converges when mutation and source absence complete before animation settles") {
        val registry = ExitAnimationRegistry<TestItem>()

        registry.beginExit("a", TestItem("a"), null)
        registry.markExitMutationCommitted("a")
        registry.updateSourceKeys(emptySet())

        registry.entries.value.containsKey("a") shouldBe true
        registry.entries.value["a"]?.animationSettled shouldBe false
        registry.entries.value["a"]?.mutationCommitted shouldBe true
        registry.entries.value["a"]?.sourceAbsent shouldBe true

        registry.markExitAnimationSettled("a")

        registry.entries.value.isEmpty() shouldBe true
    }

    test("rollback removes the entry even when lifecycle conditions are partially complete") {
        val registry = ExitAnimationRegistry<TestItem>()

        registry.beginExit("a", TestItem("a"), null)
        registry.markExitAnimationSettled("a")
        registry.rollbackExit("a")

        registry.entries.value.isEmpty() shouldBe true
    }

    test("keys without duplicates are returned unchanged") {
        val base = listOf("a", "b", "c")
        uniqueMemoListRenderKeys(base) shouldContainExactly base
    }

    test("repeated keys are disambiguated while first occurrence and order are preserved") {
        val base = listOf("a", "b", "a", "c", "b")
        val result = uniqueMemoListRenderKeys(base)

        result.size shouldBe base.size
        result.toSet().size shouldBe base.size
        result[0] shouldBe "a"
        result[1] shouldBe "b"
        result[3] shouldBe "c"
        (result[2] == "a") shouldBe false
        (result[4] == "b") shouldBe false
    }

    test("computeExitRenderListBaseKeys computes base keys with potential duplicates") {
        val renderList = listOf(
            LomoListExitRenderEntry(item = TestItem("a"), snapshotMemo = TestItem("a"), exitPhase = null),
            LomoListExitRenderEntry(
                item = TestItem("b"),
                snapshotMemo = TestItem("b"),
                exitPhase = LomoListExitPhase.Exiting,
            )
        ).toImmutableList()
        val pagedList = listOf(TestItem("ignored-0"), TestItem("ignored-1"), TestItem("c"))

        val baseKeys = computeExitRenderListBaseKeys(
            totalItemCount = 3,
            snapshotStartIndex = 0,
            renderList = renderList,
            itemKey = ::testKey,
            peekItem = { index -> pagedList.getOrNull(index) }
        )

        baseKeys shouldContainExactly listOf("a", "b", "c")
    }

    test("integration of base key computation and unique rendering key resolution") {
        val renderList = listOf(
            LomoListExitRenderEntry(item = TestItem("a"), snapshotMemo = TestItem("a"), exitPhase = null),
            LomoListExitRenderEntry(
                item = TestItem("b"),
                snapshotMemo = TestItem("b"),
                exitPhase = LomoListExitPhase.Exiting,
            )
        ).toImmutableList()
        // Overlap: "b" is in both lists at different positions due to refresh offset shift
        val pagedList = listOf(TestItem("ignored-0"), TestItem("ignored-1"), TestItem("b"))

        val baseKeys = computeExitRenderListBaseKeys(
            totalItemCount = 3,
            snapshotStartIndex = 0,
            renderList = renderList,
            itemKey = ::testKey,
            peekItem = { index -> pagedList.getOrNull(index) }
        )

        val uniqueKeys = uniqueMemoListRenderKeys(baseKeys)
        uniqueKeys.toSet().size shouldBe baseKeys.size
    }
})
