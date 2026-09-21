package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.TestConfig
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
 * - Unit under test: PagingDataCachedInRule, NoWriteOnlyStateFlowRule, NoCollaboratorDefaultArgRule
 * - Owning layer: quality (UDF contract guardrails for paging, write-only state, and collaborator wiring;
 *   quality/udf-contract.md)
 * - Priority tier: P1
 *
 * Capability:
 * - Flow<PagingData<*>> surfaces in app/src production must terminate in cachedIn(...) before exposure
 *   (Audit F10 audit-01 / D6 audit-07).
 * - A private MutableStateFlow that is only ever written (.value assignment / update receiver) and never
 *   read in its class is a leaking accumulator and must be read through the screen-state machine or deleted
 *   (Audit RF4 audit-04); write-only-flow-ok registers cross-file readers.
 * - A constructor value parameter must not default-instantiate a configured collaborator token
 *   (Bus/Registry/Coordinator) — default values mint orphan collaborators outside the composition root
 *   (Audit Q6 audit-03); collaborator-default-ok registers the exception.
 *
 * Scenarios:
 * - Given an app/src Flow<PagingData> property/function whose chain lacks cachedIn, when linted, then it is
 *   reported; a cachedIn chain, or a pure pass-through delegation without any call, is legal.
 * - Given a private write-only MutableStateFlow, when linted, then it is reported; any read occurrence
 *   (bare reference, .value read, collection) keeps it legal, as does the write-only-flow-ok marker.
 * - Given a constructor value parameter with a default whose type contains a configured token, when linted,
 *   then it is reported; without a default, without a token match, or with collaborator-default-ok it is legal.
 *
 * Observable outcomes:
 * - Registered rule presence, finding counts, and finding message content for production fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because uncached PagingData, write-only StateFlow, and defaulted
 *   collaborator constructors compile without findings.
 *
 * Excludes:
 * - Type resolution across files, non-Flow PagingData surfaces, and test-source configurations.
 *
 * Test Change Justification:
 * Reason category: Documentation-location migration
 * Old behavior/assertion being replaced: `message shouldContain "docs/udf-contract.md"`.
 * Why old assertion is no longer correct: the contract lives in quality/ now (engineering contracts sit
 *   with quality/, not with the user-facing docs/ assets), and the rules cite the moved path.
 * Coverage preserved by: the same four assertions still require each targeted finding to cite the contract
 *   path, so a rule that stops citing the contract still fails.
 * Why this is not fitting the test to the implementation: only the cited directory changed; the rule
 *   detection logic, finding counts, and the "finding must cite the contract" guard are unchanged.
 */
class UdfContractRulesTest : FunSpec({
    test("registers PagingDataCachedIn, NoWriteOnlyStateFlow, and NoCollaboratorDefaultArg in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("PagingDataCachedIn")].shouldNotBeNull()
        rules[RuleName("NoWriteOnlyStateFlow")].shouldNotBeNull()
        rules[RuleName("NoCollaboratorDefaultArg")].shouldNotBeNull()
    }

    test("PagingDataCachedIn: flags Flow<PagingData> property whose chain lacks cachedIn") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.paging.PagingData
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.map

                data class Memo(val id: String)

                class SampleViewModel {
                    val pagedMemos: Flow<PagingData<Memo>> = pager.flow.map { it }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "'pagedMemos'"
        findings.single().message shouldContain "cachedIn"
        findings.single().message shouldContain "quality/udf-contract.md"
        findings.single().message shouldContain "F10"
    }

    test("PagingDataCachedIn: allows Flow<PagingData> property whose chain applies cachedIn") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.paging.PagingData
                import androidx.paging.cachedIn
                import kotlinx.coroutines.flow.Flow

                data class Memo(val id: String)

                class SampleViewModel {
                    val pagedMemos: Flow<PagingData<Memo>> = pager.flow.cachedIn(viewModelScope)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("PagingDataCachedIn: allows uncached private intermediate with uncached-paging-ok marker") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.paging.PagingData
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.StateFlow

                data class Memo(val id: String)

                class SampleViewModel {
                    // behavior-contract: uncached-paging-ok: private intermediate; public pagedMemos is cachedIn-terminated
                    private val memoPagingData: StateFlow<PagingData<Memo>?> = pager.flow.stateIn(scope, null)

                    val pagedMemos: Flow<PagingData<Memo>> = memoPagingData.filterNotNull().cachedIn(scope)
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("PagingDataCachedIn: allows pure pass-through PagingData delegation without any call") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.paging.PagingData
                import kotlinx.coroutines.flow.Flow

                data class Memo(val id: String)

                class SampleViewModel {
                    val pagedMemos: Flow<PagingData<Memo>> = listStateHolder.pagedMemos
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("PagingDataCachedIn: flags function returning uncached Flow<PagingData> and allows cachedIn body") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "app/src/feature/SamplePager.kt",
                """
                package com.lomo.app.feature

                import androidx.paging.PagingData
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.map

                data class Memo(val id: String)

                class SamplePagerHost {
                    fun uncachedPaging(): Flow<PagingData<Memo>> = pager.flow.map { it }

                    fun cachedPaging(): Flow<PagingData<Memo>> = pager.flow.cachedIn(scope)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "'uncachedPaging'"
        findings.single().message shouldContain "quality/udf-contract.md"
    }

    test("PagingDataCachedIn: ignores non-Flow PagingData types") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.paging.PagingData

                data class Memo(val id: String)

                class SampleViewModel {
                    val snapshot: PagingData<Memo> = PagingData.empty()
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("PagingDataCachedIn: ignores Flow<PagingData> outside /app/src/") {
        val findings =
            rule("PagingDataCachedIn").findingsForSource(
                "data/src/paging/SamplePagerRepository.kt",
                """
                package com.lomo.data.paging

                import androidx.paging.PagingData
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.map

                data class Memo(val id: String)

                class SamplePagerRepository {
                    val pagedMemos: Flow<PagingData<Memo>> = pager.flow.map { it }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteOnlyStateFlow: flags private MutableStateFlow that is only written") {
        val findings =
            rule("NoWriteOnlyStateFlow").findingsForSource(
                "app/src/feature/common/SampleReplacementHolder.kt",
                """
                package com.lomo.app.feature.common

                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.update

                class SampleReplacementHolder {
                    private val _contentReplacements = MutableStateFlow<Map<String, String>>(emptyMap())

                    fun track(key: String, value: String) {
                        _contentReplacements.value += key to value
                    }

                    fun rewrite(key: String) {
                        _contentReplacements.update { replacements -> replacements - key }
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Write-only StateFlow '_contentReplacements'"
        findings.single().message shouldContain "RF4"
        findings.single().message shouldContain "quality/udf-contract.md"
        findings.single().message shouldContain "write-only-flow-ok"
    }

    test("NoWriteOnlyStateFlow: allows MutableStateFlow that is read in its class") {
        val findings =
            rule("NoWriteOnlyStateFlow").findingsForSource(
                "app/src/feature/SampleViewModel.kt",
                """
                package com.lomo.app.feature

                import androidx.lifecycle.ViewModel
                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.StateFlow

                class SampleViewModel : ViewModel() {
                    private val _query = MutableStateFlow("")

                    val query: StateFlow<String> = _query.asStateFlow()

                    fun normalizedQuery(): String = _query.value
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteOnlyStateFlow: allows write-only MutableStateFlow with write-only-flow-ok marker") {
        val findings =
            rule("NoWriteOnlyStateFlow").findingsForSource(
                "app/src/feature/common/SampleReplacementHolder.kt",
                """
                package com.lomo.app.feature.common

                import kotlinx.coroutines.flow.MutableStateFlow

                class SampleReplacementHolder {
                    // behavior-contract: write-only-flow-ok: read by the projection collector in another class
                    private val _contentReplacements = MutableStateFlow<Map<String, String>>(emptyMap())

                    fun track(key: String, value: String) {
                        _contentReplacements.value += key to value
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteOnlyStateFlow: ignores non-private MutableStateFlow") {
        val findings =
            rule("NoWriteOnlyStateFlow").findingsForSource(
                "app/src/feature/common/SampleReplacementHolder.kt",
                """
                package com.lomo.app.feature.common

                import kotlinx.coroutines.flow.MutableStateFlow

                class SampleReplacementHolder {
                    val contentReplacements = MutableStateFlow<Map<String, String>>(emptyMap())

                    fun track(key: String, value: String) {
                        contentReplacements.value += key to value
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoWriteOnlyStateFlow: ignores write-only MutableStateFlow outside /app/src/") {
        val findings =
            rule("NoWriteOnlyStateFlow").findingsForSource(
                "data/src/cache/SampleCache.kt",
                """
                package com.lomo.data.cache

                import kotlinx.coroutines.flow.MutableStateFlow
                import kotlinx.coroutines.flow.update

                class SampleCache {
                    private val _contentReplacements = MutableStateFlow<Map<String, String>>(emptyMap())

                    fun track(key: String, value: String) {
                        _contentReplacements.value += key to value
                    }

                    fun rewrite(key: String) {
                        _contentReplacements.update { replacements -> replacements - key }
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoCollaboratorDefaultArg: flags constructor default arg whose type matches configured token") {
        val findings =
            rule(
                "NoCollaboratorDefaultArg",
                TestConfig("forbiddenTypeTokens" to listOf("Bus", "Registry", "Coordinator")),
            ).findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class StoreInvalidationBus {
                    val events: List<String> = emptyList()
                }

                class SampleRepository(
                    private val bus: StoreInvalidationBus = StoreInvalidationBus(),
                )
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "'bus'"
        findings.single().message shouldContain "Q6"
        findings.single().message shouldContain "quality/udf-contract.md"
        findings.single().message shouldContain "collaborator-default-ok"
    }

    test("NoCollaboratorDefaultArg: allows constructor parameter without default") {
        val findings =
            rule(
                "NoCollaboratorDefaultArg",
                TestConfig("forbiddenTypeTokens" to listOf("Bus", "Registry", "Coordinator")),
            ).findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class StoreInvalidationBus

                class SampleRepository(
                    private val bus: StoreInvalidationBus,
                )
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoCollaboratorDefaultArg: allows default arg whose type matches no configured token") {
        val findings =
            rule(
                "NoCollaboratorDefaultArg",
                TestConfig("forbiddenTypeTokens" to listOf("Bus", "Registry", "Coordinator")),
            ).findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class InMemoryMemoRepository

                class SampleRepository(
                    private val fallback: InMemoryMemoRepository = InMemoryMemoRepository(),
                )
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoCollaboratorDefaultArg: allows collaborator-default-ok marker") {
        val findings =
            rule(
                "NoCollaboratorDefaultArg",
                TestConfig("forbiddenTypeTokens" to listOf("Bus", "Registry", "Coordinator")),
            ).findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class StoreInvalidationBus

                class SampleRepository(
                    // behavior-contract: collaborator-default-ok: process-wide singleton bus by design
                    private val bus: StoreInvalidationBus = StoreInvalidationBus(),
                )
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoCollaboratorDefaultArg: requires configured tokens to report") {
        val findings =
            rule("NoCollaboratorDefaultArg").findingsForSource(
                "data/src/repository/SampleRepository.kt",
                """
                package com.lomo.data.repository

                class StoreInvalidationBus

                class SampleRepository(
                    private val bus: StoreInvalidationBus = StoreInvalidationBus(),
                )
                """,
            )

        findings.shouldHaveSize(0)
    }
})

private fun rule(
    name: String,
    config: Config = Config.empty,
): Rule =
    LomoArchitectureRuleSetProvider()
        .instance()
        .rules[RuleName(name)]
        ?.invoke(config)
        ?: error("Rule '$name' is not registered in LomoArchitectureRuleSetProvider")

private fun Rule.findingsForSource(
    relativePath: String,
    code: String,
): List<Finding> {
    val tempDir = Files.createTempDirectory("detekt-test")
    val file = tempDir.resolve(relativePath)
    file.parent?.createDirectories()
    file.writeText(code.trimIndent())
    val ktFile = compileForTest(file)
    return lint(ktFile)
}
