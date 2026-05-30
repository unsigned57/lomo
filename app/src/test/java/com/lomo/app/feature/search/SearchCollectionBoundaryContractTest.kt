// architectural-boundary-check
package com.lomo.app.feature.search

import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: Search memo collection boundary.
 * - Owning layer: app Search feature consuming common collection state.
 * - Priority tier: P1
 * - Capability: prove Search collection-state migration is isolated from Main/Batch C APIs.
 *
 * Scenarios:
 * - Given SearchViewModel construction is inspected, when Search needs memo UI projection, then it
 *   depends on the common collection projection port instead of Main feature mapper APIs.
 * - Given SearchViewModel source is inspected as an architecture-boundary exception, when Batch C
 *   Main files are dirty, then Search source still has no dependency on MainViewModel or
 *   MainMemoListStateHolder.
 *
 * Observable outcomes:
 * - Reflection-visible SearchViewModel constructor parameter types and scoped source boundary
 *   violations.
 *
 * TDD proof:
 * - RED before the fix because SearchViewModel directly injected MemoUiMapper from
 *   com.lomo.app.feature.main instead of the common MemoCollectionProjectionMapper boundary.
 *
 * Excludes:
 * - Compose rendering, Main feature behavior, and domain/data query behavior.
 */
class SearchCollectionBoundaryContractTest : AppFunSpec() {
    init {
        test("given search collection migration when constructor inspected then search depends on common projection boundary") {
            val constructorParameterNames =
                SearchViewModel::class.java.declaredConstructors
                    .flatMap { constructor -> constructor.parameterTypes.map(Class<*>::getName) }

            constructorParameterNames.filter { typeName -> typeName == MAIN_MEMO_UI_MAPPER }.shouldBeEmpty()
            constructorParameterNames.contains(MemoCollectionProjectionMapper::class.java.name) shouldBe true
        }

        test("given dirty Main Batch C files when search source inspected then no Batch C API dependency exists") {
            val source = appSource("feature/search/SearchViewModel.kt").readText()
            val forbiddenReferences =
                FORBIDDEN_MAIN_BATCH_C_API_REFERENCES.filter { reference ->
                    source.contains(reference)
                }

            withClue("SearchViewModel must stay isolated from Main/Batch C state owners") {
                forbiddenReferences.shouldBeEmpty()
            }
        }
    }

    private fun appSource(relativePath: String): File {
        val sourceRoot = resolveModuleRoot("app").resolve("src/main/java/com/lomo/app")
        val file = sourceRoot.resolve(relativePath)
        withClue("Expected app source file exists: ${file.path}") {
            file.exists() shouldBe true
        }
        return file
    }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidates =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
            ).filterNotNull()
        return checkNotNull(
            candidates.firstOrNull { candidate ->
                candidate.name == moduleName && candidate.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from ${currentDir.path}"
        }
    }

    private companion object {
        private const val MAIN_MEMO_UI_MAPPER = "com.lomo.app.feature.main.MemoUiMapper"
        private val FORBIDDEN_MAIN_BATCH_C_API_REFERENCES =
            listOf(
                "MainViewModel",
                "MainMemoListStateHolder",
                "com.lomo.app.feature.main.MainViewModel",
                "com.lomo.app.feature.main.MainMemoListStateHolder",
            )
    }
}
