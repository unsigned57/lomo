/*
 * Behavior Contract:
 * - Unit under test: MemoSearchRepository contract.
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: keep MemoSearchRepository limited to tag paging while text search is owned by
 *   MainListQueryRepository and SearchMemosPageUseCase.
 *
 * Scenarios:
 * - Given the search repository contract is inspected, when methods are resolved from the JVM
 *   interface, then it exposes no full-list text-search method.
 * - Given the tag repository contract is inspected, when the paging method is resolved from the JVM
 *   interface, then tag paging is abstract and must be implemented by concrete repositories.
 *
 * Observable outcomes:
 * - JVM interface method names and modifiers for `getMemosByTagPagingSource`.
 *
 * TDD proof:
 * - RED before the fix because `searchMemosList` remained on the contract after the app search
 *   path moved to the main-list query port.
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

            val method = checkNotNull(methodsByName["getMemosByTagPagingSource"]) {
                "Missing MemoSearchRepository.getMemosByTagPagingSource"
            }
            withClue("MemoSearchRepository.getMemosByTagPagingSource must not have a default fallback") {
                Modifier.isAbstract(method.modifiers) shouldBe true
            }
        }
    }
}
