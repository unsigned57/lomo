/*
 * Behavior Contract:
 * - Unit under test: data DI boundary
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: keep infrastructure bindings grouped by data capability instead of a catch-all module bus.
 *
 * Scenarios:
 * - Given data DI is inspected, when infrastructure bindings are organized, then no catch-all DataModule file exists.
 * - Given sync provider registration is inspected, when data owns concrete sync repositories, then each provider is contributed through Koin multibinding.
 * - Given readiness and Markdown capabilities are bound, when DI is inspected, then both resolve
 *   from one concrete ManagedEngineSession singleton rather than opening a second native engine.
 * - Given Markdown and reminder mutation capabilities are bound, when DI is inspected, then they
 *   reach that session through the workspace mutation lease rather than directly.
 *
 * Observable outcomes:
 * - Architecture assertions over data DI source ownership and multibinding registration.
 *
 * TDD proof:
 * - RED: before this fix DataModule.kt still contains unrelated database, repository, sync, update, and media bindings.
 *
 * Excludes:
 * - Koin graph verification internals, Android runtime behavior, and repository sync behavior.
 
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands;
 *   workspace mutation lease replaces the boolean write freeze.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams. The
 *   single-session assertion is retained and strengthened: Markdown/reminder mutations must now be
 *   bound through LeasedMarkdownWorkspace, so a future binding cannot bypass the switch barrier.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */
package com.lomo.data.architecture

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class DataDiBoundaryTest : DataFunSpec() {
    init {
        test("given data DI when inspected then bindings are grouped by capability files") {
            val diRoot = resolveModuleRoot("data").resolve("src/di")
            val catchAllFile = diRoot.resolve("DataModule.kt")

            withClue("Data DI must live in capability-named files, not ${catchAllFile.path}") {
                catchAllFile.exists() shouldBe false
            }
        }

        test("given concrete sync repositories when inspected then unified providers are multibound in data") {
            val diRoot = resolveModuleRoot("data").resolve("src/di")
            val content = collectKotlinFiles(diRoot).joinToString(separator = "\n") { it.readText() }

            withClue("Data DI must declare UnifiedSyncProvider bindings") {
                content.contains("single<UnifiedSyncProvider>") shouldBe true
            }
            withClue("Git provider must be contributed by the sync capability module") {
                content.contains("GitUnifiedSyncProvider") shouldBe true
            }
            withClue("WebDAV provider must be contributed by the sync capability module") {
                content.contains("WebDavUnifiedSyncProvider") shouldBe true
            }
            withClue("S3 provider must be contributed by the sync capability module") {
                content.contains("S3UnifiedSyncProvider") shouldBe true
            }
            withClue("Inbox provider must be contributed by the sync capability module") {
                content.contains("InboxUnifiedSyncProvider") shouldBe true
            }
        }

        test("given engine readiness when DI is inspected then factory-backed binding owns close") {
            val diRoot = resolveModuleRoot("data").resolve("src/di")
            val engineModule = diRoot.resolve("EngineModule.kt").readText()
            withClue("engineModule must bind EngineReadinessRepository") {
                engineModule.contains("EngineReadinessRepository") shouldBe true
            }
            withClue("engineModule must bind one ManagedEngineSession as the Markdown workspace boundary") {
                engineModule.contains("ManagedEngineSession(") shouldBe true
                engineModule.contains("single<EngineReadinessRepository> { get<ManagedEngineSession>() }") shouldBe true
            }
            withClue("Markdown/reminder mutations must reach the session through the workspace lease") {
                engineModule.contains(
                    "LeasedMarkdownWorkspace(delegate = get<ManagedEngineSession>(), lease = get())",
                ) shouldBe true
                engineModule.contains(
                    "single<MarkdownWorkspaceRepository> { get<LeasedMarkdownWorkspace>() }",
                ) shouldBe true
                engineModule.contains(
                    "single<MarkdownReminderRepository> { get<LeasedMarkdownWorkspace>() }",
                ) shouldBe true
            }
            withClue("engineModule must open through ManagedEngineSession + BoltFfiNativeEngineFactory") {
                engineModule.contains("ManagedEngineSession") shouldBe true
                engineModule.contains("BoltFfiNativeEngineFactory") shouldBe true
            }
            withClue("engineModule must close native handles on Koin onClose") {
                engineModule.contains("onClose") shouldBe true
                engineModule.contains("withOptions") shouldBe true
            }
            withClue("engineModule must bind production WorkspaceCandidateValidator probe") {
                engineModule.contains("WorkspaceCandidateValidator") shouldBe true
                engineModule.contains("WorkspaceCandidateProbe") shouldBe true
            }
            val dataModules = diRoot.resolve("DataModules.kt").readText()
            withClue("dataModules must include engineModule") {
                dataModules.contains("engineModule") shouldBe true
            }
        }
    }

    private fun collectKotlinFiles(root: File): List<File> =
        root
            .takeIf(File::exists)
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            .orEmpty()

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("module.yaml").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
