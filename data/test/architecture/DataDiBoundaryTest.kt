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
 *
 * Observable outcomes:
 * - Architecture assertions over data DI source ownership and multibinding registration.
 *
 * TDD proof:
 * - RED: before this fix DataModule.kt still contains unrelated database, repository, sync, update, and media bindings.
 *
 * Excludes:
 * - Koin graph verification internals, Android runtime behavior, and repository sync behavior.
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
