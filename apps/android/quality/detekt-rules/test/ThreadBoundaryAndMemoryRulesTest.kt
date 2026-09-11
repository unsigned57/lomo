// architectural-boundary-check
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
 * - Unit under test: NoUnconfinedIoOrNativeRule, NoUnpaginatedFullLoadRule
 * - Owning layer: quality (runtime thread boundary and memory guardrails)
 * - Priority tier: P0
 *
 * Capability:
 * - NoUnconfinedIoOrNativeRule: flags blocking I/O calls outside Dispatchers.IO.
 * - NoUnpaginatedFullLoadRule: flags unpaginated collection queries and unbuffered readBytes.
 *
 * Scenarios:
 * - Given blocking I/O outside Dispatchers.IO, when linted, then the thread-boundary finding is reported.
 * - Given an unpaginated full collection load or readBytes, when linted, then the memory finding is reported.
 *
 * Observable outcomes:
 * - Registered rule presence and finding counts for compiled fixtures.
 *
 * TDD proof:
 * - Fails before the rules exist because unconfined I/O and unpaginated loads compile without findings.
 *
 * Excludes:
 * - Test fixtures and explicitly bounded one-shot reads.
 */
class ThreadBoundaryAndMemoryRulesTest : FunSpec({
    test("registers NoUnconfinedIoOrNative and NoUnpaginatedFullLoad in rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules
        rules[RuleName("NoUnconfinedIoOrNative")].shouldNotBeNull()
        rules[RuleName("NoUnpaginatedFullLoad")].shouldNotBeNull()
    }

    test("NoUnconfinedIoOrNative: flags readText outside withContext(Dispatchers.IO)") {
        val findings =
            rule("NoUnconfinedIoOrNative").findingsForSource(
                "app/src/feature/SampleView.kt",
                """
                package com.lomo.app.feature

                import java.io.File

                class SampleView {
                    fun loadFile(file: File): String {
                        return file.readText()
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Blocking I/O operation 'readText' executed outside Dispatchers.IO"
    }

    test("NoUnconfinedIoOrNative: allows readText inside withContext(Dispatchers.IO)") {
        val findings =
            rule("NoUnconfinedIoOrNative").findingsForSource(
                "app/src/feature/SampleView.kt",
                """
                package com.lomo.app.feature

                import java.io.File
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.withContext

                class SampleView {
                    suspend fun loadFile(file: File): String =
                        withContext(Dispatchers.IO) {
                            file.readText()
                        }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnconfinedIoOrNative: allows readText with behavior-contract comment") {
        val findings =
            rule("NoUnconfinedIoOrNative").findingsForSource(
                "app/src/feature/SampleView.kt",
                """
                package com.lomo.app.feature

                import java.io.File

                class SampleView {
                    fun loadFile(file: File): String {
                        // behavior-contract: blocking-io-ok: lightweight bootstrap read
                        return file.readText()
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnconfinedIoOrNative: flags writeText outside withContext(Dispatchers.IO)") {
        val findings =
            rule("NoUnconfinedIoOrNative").findingsForSource(
                "app/src/navigation/NavCache.kt",
                """
                package com.lomo.app.navigation

                import java.io.File

                class NavCache {
                    fun save(file: File, data: String) {
                        file.writeText(data)
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Blocking I/O operation 'writeText' executed outside Dispatchers.IO"
    }

    test("NoUnpaginatedFullLoad: flags getAllMemos returning List<Memo> without limit or pageSize") {
        val findings =
            rule("NoUnpaginatedFullLoad").findingsForSource(
                "domain/src/usecase/GetAllMemosUseCase.kt",
                """
                package com.lomo.domain.usecase

                class GetAllMemosUseCase {
                    fun getAllMemos(): List<String> {
                        return emptyList()
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Unpaginated full-dataset query 'getAllMemos'"
    }

    test("NoUnpaginatedFullLoad: allows getAllMemos with limit parameter") {
        val findings =
            rule("NoUnpaginatedFullLoad").findingsForSource(
                "domain/src/usecase/GetAllMemosUseCase.kt",
                """
                package com.lomo.domain.usecase

                class GetAllMemosUseCase {
                    fun getAllMemos(limit: Int): List<String> {
                        return emptyList()
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnpaginatedFullLoad: allows getAllMemos with behavior-contract comment") {
        val findings =
            rule("NoUnpaginatedFullLoad").findingsForSource(
                "domain/src/usecase/GetAllMemosUseCase.kt",
                """
                package com.lomo.domain.usecase

                class GetAllMemosUseCase {
                    // behavior-contract: unpaginated-ok: fixed bounded static set
                    fun getAllMemos(): List<String> {
                        return emptyList()
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }

    test("NoUnpaginatedFullLoad: flags readBytes call without behavior-contract comment") {
        val findings =
            rule("NoUnpaginatedFullLoad").findingsForSource(
                "data/src/repository/MediaRepository.kt",
                """
                package com.lomo.data.repository

                import java.io.File

                class MediaRepository {
                    fun load(file: File): ByteArray {
                        return file.readBytes()
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Unbounded memory allocation: calling 'readBytes()'"
    }

    test("NoUnpaginatedFullLoad: allows readBytes with behavior-contract full-load-ok comment") {
        val findings =
            rule("NoUnpaginatedFullLoad").findingsForSource(
                "data/src/repository/MediaRepository.kt",
                """
                package com.lomo.data.repository

                import java.io.File

                class MediaRepository {
                    fun load(file: File): ByteArray {
                        // behavior-contract: full-load-ok: small icon bounded to 32kb
                        return file.readBytes()
                    }
                }
                """,
            )

        findings.shouldHaveSize(0)
    }
})

private fun rule(name: String): Rule =
    LomoArchitectureRuleSetProvider()
        .instance()
        .rules[RuleName(name)]
        ?.invoke(Config.empty)
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
