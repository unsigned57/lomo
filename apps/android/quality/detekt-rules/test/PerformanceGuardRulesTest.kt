package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: NoLoopBoundaryIoRule, NoNestedCollectionScanRule, NoFullRebuildInLocalMutationRule
 * - Owning layer: quality (performance and anti-pattern guardrails)
 * - Priority tier: P0
 *
 * Capability:
 * - NoLoopBoundaryIoRule: flags calls to I/O, database, FFI, or repository boundaries inside loops (N+1 query amplification).
 * - NoNestedCollectionScanRule: flags linear collection searches inside outer iterations (O(N^2) Cartesian complexity).
 * - NoFullRebuildInLocalMutationRule: flags global database rebuild calls inside local single-item mutation functions.
 *
 * Scenarios:
 * - Given a loop that calls I/O or a repository, when linted, then N+1 amplification is reported.
 * - Given a nested linear collection scan, when linted, then quadratic cost is reported.
 * - Given a local mutation that triggers a full rebuild, when linted, then the rebuild is reported.
 *
 * Observable outcomes:
 * - Registered rule presence and finding counts for compiled fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because N+1 loops, nested scans, and full rebuilds compile without findings.
 *
 * Excludes:
 * - Measured hot-path benchmarks and approved indexed lookups.
 */
class PerformanceGuardRulesTest : FunSpec({
    test("registers NoLoopBoundaryIo, NoNestedCollectionScan, and NoFullRebuildInLocalMutation in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoLoopBoundaryIo")].shouldNotBeNull()
        rules[RuleName("NoNestedCollectionScan")].shouldNotBeNull()
        rules[RuleName("NoFullRebuildInLocalMutation")].shouldNotBeNull()
    }

    test("NoLoopBoundaryIo: flags calling port.getMemo inside for loop") {
        val findings =
            rule("NoLoopBoundaryIo").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun loadAll(ids: List<String>) {
                        for (id in ids) {
                            port.getMemo(id)
                        }
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Loop-boundary I/O anti-pattern"
    }

    test("NoLoopBoundaryIo: flags calling repository.commit inside forEach iteration") {
        val findings =
            rule("NoLoopBoundaryIo").findingsForSource(
                "app/src/feature/memo/SampleController.kt",
                """
                package com.lomo.app.feature.memo

                class SampleController(private val memoRepository: Any) {
                    fun saveMany(mutations: List<Any>) {
                        mutations.forEach { mutation ->
                            memoRepository.commitDocumentMutation(mutation)
                        }
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Loop-boundary I/O anti-pattern"
    }

    test("NoLoopBoundaryIo: allows in-memory list operations inside loop") {
        val findings =
            rule("NoLoopBoundaryIo").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository {
                    fun transform(items: List<String>): List<String> {
                        val result = mutableListOf<String>()
                        for (item in items) {
                            result.add(item.trim())
                        }
                        return result
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoLoopBoundaryIo: allows boundary call when marked with behavior-contract opt-out") {
        val findings =
            rule("NoLoopBoundaryIo").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun sequentialBatch(ids: List<String>) {
                        for (id in ids) {
                            // behavior-contract: loop-io-ok: sequential pacing required
                            port.getMemo(id)
                        }
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoNestedCollectionScan: flags otherList.any inside filter iteration") {
        val findings =
            rule("NoNestedCollectionScan").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    fun filterMatching(items: List<Item>, targetList: List<Item>): List<Item> {
                        return items.filter { item ->
                            targetList.any { it.id == item.id }
                        }
                    }
                }
                class Item(val id: String)
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Nested collection scan anti-pattern"
    }

    test("NoNestedCollectionScan: flags targetList.contains inside map iteration") {
        val findings =
            rule("NoNestedCollectionScan").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    fun markSelected(items: List<Item>, selectedIdList: List<String>): List<Boolean> {
                        return items.map { item ->
                            selectedIdList.contains(item.id)
                        }
                    }
                }
                class Item(val id: String)
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Nested collection linear contains anti-pattern"
    }

    test("NoNestedCollectionScan: allows scanning item's own child properties") {
        val findings =
            rule("NoNestedCollectionScan").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    fun filterTagged(items: List<Item>): List<Item> {
                        return items.filter { it.tags.any { tag -> tag == "starred" } }
                    }
                }
                class Item(val id: String, val tags: List<String>)
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoNestedCollectionScan: allows nested scan when marked with behavior-contract opt-out") {
        val findings =
            rule("NoNestedCollectionScan").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase {
                    fun filterMatching(items: List<Item>, targetList: List<Item>): List<Item> {
                        // behavior-contract: nested-scan-ok: targetList is strictly bounded to max 2 items
                        return items.filter { item ->
                            targetList.any { it.id == item.id }
                        }
                    }
                }
                class Item(val id: String)
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoFullRebuildInLocalMutation: flags startRebuild inside toggleTask function") {
        val findings =
            rule("NoFullRebuildInLocalMutation").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun toggleTask(memoId: String) {
                        port.startRebuild(batchSize = 64)
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Compensating full-rebuild anti-pattern"
    }

    test("NoFullRebuildInLocalMutation: flags refreshMemos inside updateMemoContent function") {
        val findings =
            rule("NoFullRebuildInLocalMutation").findingsForSource(
                "domain/src/usecase/SampleUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SampleUseCase(private val repo: Any) {
                    fun updateMemoContent(memoId: String, text: String) {
                        repo.refreshMemos()
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Compensating full-rebuild anti-pattern"
    }

    test("NoFullRebuildInLocalMutation: allows startRebuild inside dedicated rebuild function") {
        val findings =
            rule("NoFullRebuildInLocalMutation").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun rebuildWorkspace() {
                        port.startRebuild(batchSize = 64)
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoFullRebuildInLocalMutation: allows incremental mutation inside update function") {
        val findings =
            rule("NoFullRebuildInLocalMutation").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun updateMemoContent(memoId: String, text: String) {
                        port.applyMemoCommand(memoId, text)
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoFullRebuildInLocalMutation: allows full rebuild with explicit opt-out comment") {
        val findings =
            rule("NoFullRebuildInLocalMutation").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class SampleRepository(private val port: Any) {
                    fun updateWithFullRebuild(memoId: String) {
                        // behavior-contract: full-rebuild-ok: database schema version bump requires full rebuild
                        port.startRebuild(batchSize = 64)
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }
})

private fun rule(
    name: String,
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName(name)]) {
        "Expected rule '$name' to be registered."
    }.invoke(config)

private fun Rule.findingsForSource(
    relativePath: String,
    code: String,
): List<Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-perf-guard-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
