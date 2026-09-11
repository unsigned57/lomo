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
 * - Unit under test: NoStatefulRepositoryOrUseCaseRule, NoAdHocMemoryCacheRule
 * - Owning layer: quality (state safety and single source of truth guardrails)
 * - Priority tier: P0
 *
 * Capability:
 * - NoStatefulRepositoryOrUseCaseRule: rejects mutable 'var' member fields in Repository and UseCase classes.
 * - NoAdHocMemoryCacheRule: rejects ad-hoc in-memory Map/Cache member properties in domain and data layers without explicit behavior contract.
 *
 * Scenarios:
 * - Given a Repository or UseCase with a var member, when linted, then stateful ownership is reported.
 * - Given an ad-hoc Map/Cache member in domain or data without a behavior-contract, when linted, then it is reported.
 *
 * Observable outcomes:
 * - Registered rule presence and finding counts for compiled fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because var members and in-memory caches compile without findings.
 *
 * Excludes:
 * - ViewModel state machines and approved behavior-contract caches.
 */
class StateSafetyRulesTest : FunSpec({
    test("registers NoStatefulRepositoryOrUseCase and NoAdHocMemoryCache in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoStatefulRepositoryOrUseCase")].shouldNotBeNull()
        rules[RuleName("NoAdHocMemoryCache")].shouldNotBeNull()
    }

    test("NoStatefulRepositoryOrUseCase: flags var member property in UseCase class") {
        val findings =
            rule("NoStatefulRepositoryOrUseCase").findingsForSource(
                "domain/src/usecase/SyncDataUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SyncDataUseCase {
                    private var lastSyncTime: Long = 0L

                    fun execute() {}
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Stateful 'var' property forbidden"
    }

    test("NoStatefulRepositoryOrUseCase: flags var member property in Repository class") {
        val findings =
            rule("NoStatefulRepositoryOrUseCase").findingsForSource(
                "data/src/repository/UserDataRepositoryImpl.kt",
                """
                package com.lomo.data.repository

                class UserDataRepositoryImpl {
                    private var cachedToken: String? = null
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Stateful 'var' property forbidden"
    }

    test("NoStatefulRepositoryOrUseCase: allows val property in UseCase class") {
        val findings =
            rule("NoStatefulRepositoryOrUseCase").findingsForSource(
                "domain/src/usecase/SyncDataUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SyncDataUseCase(
                    private val dependency: String,
                ) {
                    val status: String = "IDLE"
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoStatefulRepositoryOrUseCase: allows local var inside function in UseCase class") {
        val findings =
            rule("NoStatefulRepositoryOrUseCase").findingsForSource(
                "domain/src/usecase/SyncDataUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SyncDataUseCase {
                    fun calculate(): Int {
                        var total = 0
                        total += 1
                        return total
                    }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoStatefulRepositoryOrUseCase: allows var with behavior-contract comment") {
        val findings =
            rule("NoStatefulRepositoryOrUseCase").findingsForSource(
                "domain/src/usecase/SyncDataUseCase.kt",
                """
                package com.lomo.domain.usecase

                class SyncDataUseCase {
                    // behavior-contract: stateful-var-ok: legacy synchronization slot
                    private var activeSlot: Int = 0
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoAdHocMemoryCache: flags mutableMapOf property in Repository class") {
        val findings =
            rule("NoAdHocMemoryCache").findingsForSource(
                "data/src/repository/MemoStoreRepository.kt",
                """
                package com.lomo.data.repository

                class MemoStoreRepository {
                    private val cache = mutableMapOf<String, String>()
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Ad-hoc in-memory cache detected"
    }

    test("NoAdHocMemoryCache: flags ConcurrentHashMap property in UseCase class") {
        val findings =
            rule("NoAdHocMemoryCache").findingsForSource(
                "domain/src/usecase/ResolveMemoUseCase.kt",
                """
                package com.lomo.domain.usecase

                import java.util.concurrent.ConcurrentHashMap

                class ResolveMemoUseCase {
                    private val memoCache = ConcurrentHashMap<String, String>()
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Ad-hoc in-memory cache detected"
    }

    test("NoAdHocMemoryCache: allows local mutableMapOf inside function") {
        val findings =
            rule("NoAdHocMemoryCache").findingsForSource(
                "domain/src/usecase/ResolveMemoUseCase.kt",
                """
                package com.lomo.domain.usecase

                class ResolveMemoUseCase {
                    fun parse(): Map<String, String> {
                        val temp = mutableMapOf<String, String>()
                        temp["a"] = "b"
                        return temp
                    }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("NoAdHocMemoryCache: allows cache property with behavior-contract comment") {
        val findings =
            rule("NoAdHocMemoryCache").findingsForSource(
                "data/src/repository/MemoStoreRepository.kt",
                """
                package com.lomo.data.repository

                class MemoStoreRepository {
                    // behavior-contract: managed-cache-ok: invalidation-bound cache
                    private val cache = mutableMapOf<String, String>()
                }
                """,
            )

        findings shouldBe emptyList()
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
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
