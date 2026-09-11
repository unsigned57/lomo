/*
 * Behavior Contract:
 * - Unit under test: MemoSearchRepository contract.
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: keep MemoSearchRepository as the dual-mode text-search and tag paging owner.
 *
 * Scenarios:
 * - Given the search repository contract is inspected, when methods are resolved from the JVM
 *   interface, then it exposes no full-list text-search method.
 * - Given the contract is inspected, when paging methods are resolved, then tag paging and
 *   dual-mode search paging are abstract and must be implemented by concrete repositories.
 *
 * Observable outcomes:
 * - JVM interface method names and modifiers for `getMemosByTagPagingSource` and
 *   `searchPagingSource`.
 *
 * TDD proof:
 * - RED before dual-mode search because `searchPagingSource` was absent after text search moved
 *   off the removed `searchMemosList` method.
 *
 * Excludes:
 * - Data-layer SQL pagination correctness, fake repository storage behavior,
 *   and UI paging consumption.
 */
package com.lomo.domain.repository

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

class MemoSearchRepositoryContractTest : DomainFunSpec() {
    init {
        test("search and tag page methods are implementation requirements") {
            val methodsByName =
                MemoSearchRepository::class.java.methods
                    .associateBy { method -> method.name }

            methodsByName.containsKey("searchMemosList") shouldBe false

            val tagMethod =
                checkNotNull(methodsByName["getMemosByTagPagingSource"]) {
                    "Missing MemoSearchRepository.getMemosByTagPagingSource"
                }
            withClue("MemoSearchRepository.getMemosByTagPagingSource must not have a default fallback") {
                Modifier.isAbstract(tagMethod.modifiers) shouldBe true
            }

            val searchMethod =
                checkNotNull(methodsByName["searchPagingSource"]) {
                    "Missing MemoSearchRepository.searchPagingSource"
                }
            withClue("MemoSearchRepository.searchPagingSource must not have a default fallback") {
                Modifier.isAbstract(searchMethod.modifiers) shouldBe true
            }
        }
    }
}
