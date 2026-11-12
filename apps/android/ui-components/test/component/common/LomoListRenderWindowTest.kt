package com.lomo.ui.component.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: preserve render cardinality and source identity while deletion races paging;
 * owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given a visible exit, when the source drops it first, then render keys/count stay stable
 *   and every survivor accesses its own source rank; the retained row has no source access.
 * - Given trailing placeholders, then retention shifts their source ranks without dropping one.
 * - Given multiple adjacent exits or deletion of the only row, then cardinality remains exact.
 * - Given settled retention removal, then each survivor returns to the canonical source rank.
 * - Given an invalid window, then it is rejected before list rendering.
 * Observable outcomes: actual merged render keys, item count and rendered-to-source indices.
 * TDD proof: the related enter regression failed with rank-300; deletion's original list count
 * was the raw source count (19 with 20 rendered rows) and accesses used clamped render indices.
 * GREEN: ./kotlin test --include-module=ui-components
 * --include-classes='com.lomo.ui.component.common.LomoListRenderWindowTest'
 * Excludes: physical animation frames, paging driver and persistent writes.
 */
class LomoListRenderWindowTest :
    FunSpec({
        test(
            "given source deletion before fade completes then render count and identities stay fixed while accesses follow survivors",
        ) {
            val registry = ExitAnimationRegistry<String>()
            registry.beginExit("b", "b", "a")
            fun window(source: List<String>, count: Int): Pair<List<String>, LomoListRenderWindow> {
                val keys = resolveExitRenderList(source, { it }, registry.entries.value).map { it.item }
                return keys to LomoListRenderWindow(count, 0, source, keys)
            }
            val (beforeKeys, before) = window(listOf("a", "b", "c", "d"), 4)
            val (afterKeys, after) = window(listOf("a", "c", "d"), 3)
            afterKeys shouldBe beforeKeys
            after.itemCount shouldBe before.itemCount
            (0 until after.itemCount).map(after::sourceIndexAt) shouldBe listOf(0, null, 1, 2)
            registry.markExitMutationCommitted("b")
            registry.updateSourceKeys(setOf("a", "c", "d"))
            registry.markExitAnimationSettled("b")
            val (settledKeys, settled) = window(listOf("a", "c", "d"), 3)
            settledKeys shouldBe listOf("a", "c", "d")
            settled.itemCount shouldBe 3
            (0 until settled.itemCount).map(settled::sourceIndexAt) shouldBe listOf(0, 1, 2)
        }

        test(
            "given deep loaded rows and trailing placeholders then retention preserves the complete placeholder space",
        ) {
            val window = LomoListRenderWindow(1000, 300, listOf("a", "c"), listOf("a", "b", "c"))
            window.itemCount shouldBe 1001
            listOf(299, 300, 301, 302, 303, 1000).map(window::sourceIndexAt) shouldBe
                listOf(299, 300, null, 301, 302, 999)
            window.sourceIndexAt(-1) shouldBe null
            window.sourceIndexAt(1001) shouldBe null
        }

        test(
            "given adjacent deletions when the source drops both then retained order and survivor source ranks are stable",
        ) {
            val registry = ExitAnimationRegistry<String>()
            registry.beginExits(
                listOf(
                    ExitAnimationRegistry.ExitRequest("b", "b", "a"),
                    ExitAnimationRegistry.ExitRequest("c", "c", "b"),
                ),
            )
            val source = listOf("a", "d")
            val keys = resolveExitRenderList(source, { it }, registry.entries.value).map { it.item }
            keys shouldBe listOf("a", "b", "c", "d")
            val window = LomoListRenderWindow(2, 0, source, keys)
            window.itemCount shouldBe 4
            (0 until window.itemCount).map(window::sourceIndexAt) shouldBe listOf(0, null, null, 1)
        }

        test("given deletion of the only row then its exit keeps a render slot until completion") {
            val window = LomoListRenderWindow(0, 0, emptyList(), listOf("only"))
            window.itemCount shouldBe 1
            window.sourceIndexAt(0) shouldBe null
        }

        test("given no exits then source ranks pass through unchanged") {
            val window = LomoListRenderWindow(100, 10, listOf("a", "b"), listOf("a", "b"))
            window.itemCount shouldBe 100
            listOf(0, 10, 11, 99).map(window::sourceIndexAt) shouldBe listOf(0, 10, 11, 99)
        }

        test("given a window outside the source or one losing a survivor then invalid presentation is rejected") {
            shouldThrow<IllegalArgumentException> { LomoListRenderWindow(2, 2, listOf("a"), listOf("a")) }
            shouldThrow<IllegalArgumentException> { LomoListRenderWindow(2, 0, listOf("a", "b"), listOf("a", "c")) }
        }
    })
