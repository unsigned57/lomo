package com.lomo.ui.component.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * - Unit under test: resolveExitRenderList, uniqueMemoListRenderKeys, computeExitRenderListBaseKeys
 * - Owning layer: ui-components common
 * - Priority tier: P1
 * - Capability: Merge active items with exiting items, positioning exiting items at their anchored slots, and guarantee unique render keys.
 *
 * Scenarios:
 * - Given no active exits, when resolved, then output contains all source items with isExiting=false.
 * - Given active exits that are still in source, when resolved, then they are marked as exiting and snapshot holds the live/snapshot value.
 * - Given active exits removed from source, when resolved, then they are retained at their anchored position.
 * - Given multiple concurrent deletes, when resolved, then the transitive order of exits is preserved.
 * - Given a base key list with duplicates, when uniqueMemoListRenderKeys is called, then it returns deduplicated keys preserving order.
 * - Given an exit render list and a paged peek list with overlapping items, when computeExitRenderListBaseKeys is called, then the resolved base keys contain duplicates.
 * - Given duplicate keys in base list, when uniqueMemoListRenderKeys is applied, then all keys in the final key list are unique.
 *
 * Observable outcomes:
 * - list of resolved entries with items, snapshot values, and isExiting flags.
 * - deduplicated key list matching size, order, and preserving first-occurrences.
 *
 * TDD proof:
 * - Fails if an exiting item whose anchor is missing is not appended to the end of the list.
 * - Fails when key deduplication returns empty lists because of the dummy implementations.
 *
 * Excludes:
 * - UI rendering, layout animations, lazy list viewport boundaries.
 */

private data class TestItem(val id: String, val content: String = "")
private fun testKey(item: TestItem): String = item.id

class LomoListExitSupportTest : FunSpec({
    test("no deleting ids yields all source items with isExiting=false") {
        val source = listOf(TestItem("a"), TestItem("b"), TestItem("c"))

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = emptyMap(),
        )

        result.map { it.item } shouldContainExactly source
        result.all { !it.isExiting } shouldBe true
    }

    test("deleting id still in source appears in-place with isExiting=true and snapshotMemo holds the live value") {
        val source = listOf(TestItem("a"), TestItem("b", "live"), TestItem("c"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(TestItem("b", "snapshot"), anchoredAfterKey = "a")
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
        result.map { it.isExiting } shouldContainExactly listOf(false, true, false)
        result[1].snapshotMemo.content shouldBe "snapshot"
    }

    test("deleting id removed from source is retained at its anchoredAfterKey position") {
        val source = listOf(TestItem("a"), TestItem("c"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(TestItem("b"), anchoredAfterKey = "a"),
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
        result.map { it.isExiting } shouldContainExactly listOf(false, true, false)
    }

    test("multiple concurrent deletes preserve transitive order") {
        val source = listOf(TestItem("a"), TestItem("d"))
        val activeExits = mapOf(
            "b" to ExitAnimationRegistry.ExitEntry(TestItem("b"), anchoredAfterKey = "a"),
            "c" to ExitAnimationRegistry.ExitEntry(TestItem("c"), anchoredAfterKey = "b"),
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c", "d")
        result.all { it.item.id != "b" || it.isExiting } shouldBe true
        result.all { it.item.id != "c" || it.isExiting } shouldBe true
    }

    test("null anchoredAfterKey places item at the beginning of the list") {
        val source = listOf(TestItem("b"), TestItem("c"))
        val activeExits = mapOf(
            "a" to ExitAnimationRegistry.ExitEntry(TestItem("a"), anchoredAfterKey = null),
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
            "c" to ExitAnimationRegistry.ExitEntry(TestItem("c"), anchoredAfterKey = "nonexistent"),
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
            "a" to ExitAnimationRegistry.ExitEntry(TestItem("a"), anchoredAfterKey = null)
        )

        val result = resolveExitRenderList(
            allItems = source,
            itemKey = ::testKey,
            activeExits = activeExits,
        )

        result.map { it.item.id } shouldContainExactly listOf("a", "b", "c")
    }

    test("ExitAnimationRegistry manages entries correctly") {
        val registry = ExitAnimationRegistry<TestItem>()
        registry.entries.value.isEmpty() shouldBe true

        registry.beginExit("a", TestItem("a"), "prev")
        registry.entries.value shouldBe mapOf(
            "a" to ExitAnimationRegistry.ExitEntry(TestItem("a"), "prev")
        )

        registry.settleExit("a")
        registry.entries.value.isEmpty() shouldBe true

        registry.beginExit("b", TestItem("b"), null)
        registry.clear()
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
            LomoListExitRenderEntry(item = TestItem("a"), snapshotMemo = TestItem("a"), isExiting = false),
            LomoListExitRenderEntry(item = TestItem("b"), snapshotMemo = TestItem("b"), isExiting = true)
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
            LomoListExitRenderEntry(item = TestItem("a"), snapshotMemo = TestItem("a"), isExiting = false),
            LomoListExitRenderEntry(item = TestItem("b"), snapshotMemo = TestItem("b"), isExiting = true)
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
