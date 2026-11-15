package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.common.uniqueMemoListRenderKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: uniqueMemoListRenderKeys
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: ensure LazyColumn item-key lists are globally unique even when underlying memo ids repeat, preventing Compose "Key was already used" crashes.
 *
 * Scenarios:
 * - Given a list of keys without duplicates, when keys are resolved, then the list is returned unchanged.
 * - Given a list of keys with repeated memo ids, when keys are resolved, then duplicates are disambiguated while preserving first-occurrence order.
 * - Given a list with placeholder keys and repeated memo ids, when keys are resolved, then placeholder keys are preserved and only duplicated memo ids are disambiguated.
 * - Given a pathological input where a later raw key collides with a disambiguation suffix, when keys are resolved, then all keys remain unique.
 *
 * Observable outcomes:
 * - Returned key list order, size, first-occurrence preservation, and uniqueness.
 *
 * TDD proof:
 * - Fails before the fix because there is no de-duplication, so repeated memo ids reach LazyColumn verbatim and crash Compose.
 *
 * Excludes:
 * - Compose runtime, LazyPagingItems internals, and how base keys are resolved per index.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoListRenderKeyUniqueness.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including keys without duplicates are returned unchanged;
 * repeated memo ids are disambiguated while first occurrence and order are preserved.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoListRenderKeyUniquenessTest : AppFunSpec() {
    init {
        test("keys without duplicates are returned unchanged") {
            val base = listOf("a", "b", "c")

            uniqueMemoListRenderKeys(base) shouldContainExactly base
        }

        test("repeated memo ids are disambiguated while first occurrence and order are preserved") {
            val base = listOf("a", "b", "a", "c", "b")

            val result = uniqueMemoListRenderKeys(base)

            result.size shouldBe base.size
            result.toSet().size shouldBe base.size // all unique
            result[0] shouldBe "a"
            result[1] shouldBe "b"
            result[3] shouldBe "c"
            (result[2] == "a") shouldBe false
            (result[4] == "b") shouldBe false
        }

        test("placeholder keys are preserved and only the duplicated memo id is disambiguated") {
            val base = listOf("paging-placeholder-0", "memo-1", "memo-1")

            val result = uniqueMemoListRenderKeys(base)

            result[0] shouldBe "paging-placeholder-0"
            result[1] shouldBe "memo-1"
            (result[2] == "memo-1") shouldBe false
            result.toSet().size shouldBe 3
        }

        test("a base key that already collides with a disambiguation candidate still stays unique") {
            // Pathological input where a later raw key equals the suffix generated for an earlier repeat.
            val base = listOf("x", "x", "x\u0000dup-1")

            val result = uniqueMemoListRenderKeys(base)

            result.toSet().size shouldBe base.size
            result[0] shouldBe "x"
        }

        test("empty input yields empty output") {
            uniqueMemoListRenderKeys(emptyList()) shouldContainExactly emptyList()
        }
    }
}
