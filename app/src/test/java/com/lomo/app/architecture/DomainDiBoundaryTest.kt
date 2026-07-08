/*
 * Behavior Contract:
 * - Unit under test: app domain DI boundary
 * - Owning layer: app
 * - Priority tier: P0
 * - Capability: keep domain use-case wiring grouped by capability and keep sync provider registration outside app.
 *
 * Scenarios:
 * - Given app DI is inspected, when domain bindings are organized, then no catch-all DomainBindingsModule file exists.
 * - Given sync providers are extended, when app DI is inspected, then app consumes the registry without manually constructing concrete providers.
 *
 * Observable outcomes:
 * - Architecture assertions over production source ownership and provider registration boundary.
 *
 * TDD proof:
 * - RED: before this fix DomainBindingsModule.kt still exists and manually constructs Git/WebDAV/S3/Inbox providers.
 *
 * Excludes:
 * - Koin graph verification internals, data repository bindings, and use-case behavior.
 */
package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class DomainDiBoundaryTest : AppFunSpec() {
    init {
        test("given app domain DI when inspected then bindings are grouped by capability files") {
            val diRoot = resolveModuleRoot("app").resolve("src/main/java/com/lomo/app/di")
            val catchAllFile = diRoot.resolve("DomainBindingsModule.kt")

            withClue("Domain use-case DI must live in capability-named files, not ${catchAllFile.path}") {
                catchAllFile.exists() shouldBe false
            }
        }

        test("given sync provider extension when app DI is inspected then app does not manually construct provider list") {
            val diFiles = collectKotlinFiles(resolveModuleRoot("app").resolve("src/main/java/com/lomo/app/di"))
            val offenders =
                diFiles.filter { file ->
                    val content = file.readText()
                    CONCRETE_PROVIDER_CONSTRUCTORS.any { constructor -> content.contains(constructor) }
                }

            withClue("App DI must consume SyncProviderRegistry from multibindings. Offenders: ${offenders.paths()}") {
                offenders.isEmpty() shouldBe true
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
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }

    private fun List<File>.paths(): String = joinToString { it.path }

    private companion object {
        val CONCRETE_PROVIDER_CONSTRUCTORS =
            listOf(
                "GitUnifiedSyncProvider(",
                "WebDavUnifiedSyncProvider(",
                "S3UnifiedSyncProvider(",
                "InboxUnifiedSyncProvider(",
            )
    }
}
