/*
 * Behavior Contract:
 * - Unit under test: AppLayerBoundaryTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenarios:
 * - Given app sources, when scanned, then they do not import `com.lomo.data`.
 * - Given the app module file, when parsed, then `//data` is runtime-only.
 * - Given the paged list stack, when inspected, then TopViewportMemoId is gone and MemoCardList
 *   delegates to MemoListContent with contentType.
 *
 * - Behavior focus: test behavioral outcomes of AppLayerBoundaryTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.architecture

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class AppLayerBoundaryTest : AppFunSpec() {
    private val kotlinFileExtension = "kt"
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src")
    private val moduleFile = moduleRoot.resolve("module.yaml")

    init {
        test("app source does not reference data layer package") {
            val kotlinFiles = collectKotlinFiles()
            withClue("No Kotlin sources found in app/src") { ((kotlinFiles.isNotEmpty())) shouldBe true }

            val offenders = kotlinFiles.filter(::containsDataLayerReference)
            withClue("App layer must not reference data layer package. Offenders: ${offenders.joinToString { it.path }}") { ((offenders.isEmpty())) shouldBe true }
        }

        test("widget receiver lives in a projection-only process") {
            val manifest = moduleRoot.resolve("src/AndroidManifest.xml").readText()
            val widgetReceiver =
                Regex(
                    """<receiver\b[^>]*android:name="\.widget\.LomoWidgetReceiver"[^>]*>""",
                    setOf(RegexOption.DOT_MATCHES_ALL),
                ).find(manifest)
                    ?.value
            withClue("LomoWidgetReceiver must be declared") {
                (widgetReceiver != null) shouldBe true
            }
            withClue("widget receiver must run in :widget so a Glance wake does not own the engine") {
                widgetReceiver.orEmpty().contains("""android:process=":widget"""") shouldBe true
            }
        }

        test("static baseline profile preheats data engine JNI and Koin") {
            val profile = moduleRoot.resolve("src/main/baseline-prof.txt").readText()
            val rules = moduleRoot.resolve("baseline-rules.txt").readText()
            withClue("static baseline must name data DI") {
                profile.contains("Lcom/lomo/data/di/") shouldBe true
            }
            withClue("static baseline must name ManagedEngineSession") {
                profile.contains("Lcom/lomo/data/engine/ManagedEngineSession;") shouldBe true
            }
            withClue("static baseline must name JNI LomoEngine") {
                profile.contains("Lcom/lomo/nativebridge/LomoEngine;") shouldBe true
            }
            withClue("static baseline must name Koin") {
                profile.contains("Lorg/koin/core/Koin;") shouldBe true
            }
            withClue("baseline rules must cover data engine") {
                rules.contains("com/lomo/data/engine/") shouldBe true
            }
            withClue("baseline rules must cover data DI") {
                rules.contains("com/lomo/data/di/") shouldBe true
            }
            withClue("baseline rules must cover nativebridge") {
                rules.contains("com/lomo/nativebridge/") shouldBe true
            }
            withClue("baseline rules must cover Koin") {
                rules.contains("org/koin/core/") shouldBe true
            }
        }

        test("application starts workspace ownership only in the default process") {
            val source = sourceRoot.resolve("LomoApplication.kt").readText()
            withClue("LomoApplication must consult WorkspaceProcessDuty before startup and lifecycle engine work") {
                source.contains("WorkspaceProcessDuty.ownsNativeEngine") shouldBe true
            }
        }

        test("main list keeps one paged stack and deletes dead viewport snapshot helpers") {
            withClue("TopViewportMemoId is an unused Room-era helper and must not exist") {
                sourceRoot.resolve("feature/main/TopViewportMemoId.kt").exists() shouldBe false
            }
            val memoCardList = sourceRoot.resolve("feature/memo/MemoCardListAnimation.kt").readText()
            withClue("MemoCardList must delegate to MemoListContent so search/tag inherit contentType") {
                memoCardList.contains("MemoListContent(") shouldBe true
            }
            val pagedList = sourceRoot.resolve("feature/main/PagedMemoListContent.kt").readText()
            withClue("the paged list stack must bucket rows by contentType") {
                pagedList.contains("contentType") shouldBe true
            }
        }
    }

    init {
        test("app module keeps data dependency runtime-only") {
            val content = moduleFile.readText()
            val offenders =
                DATA_DEPENDENCY_PATTERN
                    .findAll(content)
                    .filterNot { dependency -> dependency.groupValues.getOrNull(1) == "runtime-only" }
                    .map { dependency -> dependency.value.trim() }
                    .toList()

            withClue("app/module.yaml must keep //data runtime-only. Offenders: ${offenders.joinToString()}") { ((offenders.isNotEmpty())) shouldBe false }
        }
    }

    private fun collectKotlinFiles(): List<File> =
        sourceRoot
            .takeIf(File::exists)
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == kotlinFileExtension }
            ?.toList()
            .orEmpty()

    private fun containsDataLayerReference(file: File): Boolean {
        val content = file.readText()
        if (DATA_IMPORT_PATTERN.containsMatchIn(content)) return true
        val nonImportOrPackageContent = stripImportAndPackageLines(content)
        return DATA_FQCN_PATTERN.containsMatchIn(nonImportOrPackageContent)
    }

    private fun stripImportAndPackageLines(content: String): String =
        content
            .lineSequence()
            .filterNot { IMPORT_OR_PACKAGE_LINE_PATTERN.containsMatchIn(it) }
            .joinToString(separator = "\n")

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

    private companion object {
        val DATA_IMPORT_PATTERN = Regex("""(?m)^\s*import\s+com\.lomo\.data(?:\.|$)""")
        val DATA_FQCN_PATTERN = Regex("""\bcom\.lomo\.data\.[A-Za-z_]\w*""")
        val IMPORT_OR_PACKAGE_LINE_PATTERN = Regex("""^\s*(import|package)\s+""")

        val DATA_DEPENDENCY_PATTERN = Regex("""(?m)^\s*-\s*//data(?::\s*([A-Za-z-]+))?\s*$""")
    }
}
