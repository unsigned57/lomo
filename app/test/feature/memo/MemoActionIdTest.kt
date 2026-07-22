package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Behavior Contract:
 * - Unit under test: [MemoActionId] storage-key parse + default menu order.
 * - Owning layer: app memo menu identity (presentation keys, not Compose UI).
 * - Priority tier: P1
 * - Capability: menu action identity round-trips through storage keys; unknown/blank keys fail closed.
 *
 * Scenarios:
 * - Given each enum storage key (and padded variants), when fromStorageKey runs, then the matching id is returned.
 * - Given unknown or blank raw keys, when fromStorageKey runs, then null is returned.
 * - Given defaultMemoActionOrder, when listed, then every enum storage key appears exactly once in enum order.
 *
 * Observable outcomes: [MemoActionId] membership and order list equality.
 * TDD proof: RED if storageKey mapping or default order drifts without intentional product change.
 * Excludes: Compose menu rendering, command handlers, lifecycle side effects.
 */
class MemoActionIdTest : AppFunSpec() {
    init {
        test("fromStorageKey maps known keys and trims whitespace") {
            MemoActionId.entries.forEach { id ->
                MemoActionId.fromStorageKey(id.storageKey) shouldBe id
                MemoActionId.fromStorageKey(" ${id.storageKey} ") shouldBe id
            }
        }

        test("fromStorageKey rejects unknown and blank keys") {
            MemoActionId.fromStorageKey("not-an-action").shouldBeNull()
            MemoActionId.fromStorageKey("").shouldBeNull()
            MemoActionId.fromStorageKey("   ").shouldBeNull()
        }

        test("defaultMemoActionOrder lists every storage key once in enum order") {
            defaultMemoActionOrder() shouldContainExactly MemoActionId.entries.map(MemoActionId::storageKey)
        }
    }
}
