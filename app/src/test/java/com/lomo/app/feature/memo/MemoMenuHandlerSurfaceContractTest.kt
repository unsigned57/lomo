// architectural-boundary-check
/*
 * Behavior Contract:
 * - Unit under test: app feature memo menu handler surface.
 * - Owning layer: app/feature/memo.
 * - Priority tier: P1.
 * - Capability: keep app feature menu orchestration on the canonical MemoMenuCommandHandler surface.
 *
 * Scenarios:
 * - Given app feature menu host sources, when the public composable surface is inspected, then
 *   MemoInteractionHost and MemoMenuBinder expose only handler-first entrypoints.
 * - Given Tag, Gallery, and GalleryReel menu callsites, when their source surface is inspected,
 *   then each builds a MemoMenuCommandHandler before passing it to the host or binder.
 * - Given LAN share and action-order behavior at those callsites, when the handler builder is
 *   inspected, then payload content/timestamp and scoped action-order callbacks remain wired.
 *
 * Observable outcomes:
 * - Boundary assertions identify forbidden flattened app overloads and target callsites that bypass
 *   the handler-first app API.
 *
 * TDD proof:
 * - Fails before the M3 migration because MemoInteractionHost and MemoMenuBinder still expose
 *   flattened app legacy overloads, and Tag/Gallery/GalleryReel callsites pass callbacks directly.
 *
 * Excludes:
 * - ui-components presentation callback APIs, Compose rendering, Android share intents, and editor
 *   input-sheet parameter surfaces.
 */
package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class MemoMenuHandlerSurfaceContractTest : AppFunSpec() {
    private val appSourceRoot = resolveModuleRoot("app").resolve("src/main/java")

    init {
        test("given app memo host sources when inspected then only handler-first app APIs remain") {
            val binderSource = appSource("com/lomo/app/feature/memo/MemoMenuBinder.kt").readText()
            val hostSource = appSource("com/lomo/app/feature/memo/MemoInteractionHost.kt").readText()

            assertOnlyHandlerFirstComposable(
                source = binderSource,
                functionName = "MemoMenuBinder",
                handlerParameter = "commandHandler: MemoMenuCommandHandler",
            )
            assertOnlyHandlerFirstComposable(
                source = hostSource,
                functionName = "MemoInteractionHost",
                handlerParameter = "menuCommandHandler: MemoMenuCommandHandler",
            )
        }

        test("given tag gallery and reel callsites when inspected then they build and pass menu command handlers") {
            assertHandlerFirstCallsite(
                path = "com/lomo/app/feature/tag/TagFilterScreen.kt",
                hostCall = "MemoInteractionHost(",
                passParameter = "menuCommandHandler = memoMenuCommandHandler",
                actionScope = "MemoActionOrderScopes.TAG",
                actionUsageCallback = "viewModel::recordMemoActionUsage",
                actionOrderCallback = "viewModel.updateMemoActionOrder",
            )
            assertHandlerFirstCallsite(
                path = "com/lomo/app/feature/gallery/GalleryScreen.kt",
                hostCall = "MemoMenuBinder(",
                passParameter = "commandHandler = memoMenuCommandHandler",
                actionScope = "MemoActionOrderScopes.GALLERY",
                actionUsageCallback = "viewModel.recordGalleryMemoActionUsage",
                actionOrderCallback = "viewModel.updateGalleryMemoActionOrder",
            )
            assertHandlerFirstCallsite(
                path = "com/lomo/app/navigation/GalleryReelNavigation.kt",
                hostCall = "MemoMenuBinder(",
                passParameter = "commandHandler = memoMenuCommandHandler",
                actionScope = "MemoActionOrderScopes.GALLERY",
                actionUsageCallback = "mainViewModel.recordGalleryMemoActionUsage",
                actionOrderCallback = "mainViewModel.updateGalleryMemoActionOrder",
            )
        }
    }

    private fun assertOnlyHandlerFirstComposable(
        source: String,
        functionName: String,
        handlerParameter: String,
    ) {
        val functionCount = Regex("""fun\s+$functionName\s*\(""").findAll(source).count()
        withClue("$functionName must expose one app feature API entrypoint") {
            functionCount shouldBe 1
        }
        withClue("$functionName must expose the handler-first parameter") {
            source.contains(handlerParameter) shouldBe true
        }
        withClue("$functionName must not keep the flattened share-card callback surface") {
            source.contains("shareCardShowTime: Boolean") shouldBe false
        }
        withClue("$functionName must not keep the flattened LAN share callback surface") {
            source.contains("onLanShare:") shouldBe false
        }
        withClue("$functionName must not keep the flattened action-order callback surface") {
            source.contains("onMemoActionInvoked:") shouldBe false
        }
    }

    private fun assertHandlerFirstCallsite(
        path: String,
        hostCall: String,
        passParameter: String,
        actionScope: String,
        actionUsageCallback: String,
        actionOrderCallback: String,
    ) {
        val source = appSource(path).readText()
        val handlerIndex = source.indexOf("rememberMemoMenuCommandHandler(")
        val hostIndex = source.indexOf(hostCall)

        withClue("$path must build MemoMenuCommandHandler before invoking $hostCall") {
            (handlerIndex >= 0 && hostIndex > handlerIndex) shouldBe true
        }
        withClue("$path must pass the handler into $hostCall") {
            source.contains(passParameter) shouldBe true
        }
        withClue("$path must keep scoped action ordering on the handler presentation state") {
            source.contains(actionScope) shouldBe true
        }
        withClue("$path must route LAN share payload through MemoMenuLanShareRequest") {
            source.contains("{ request -> onNavigateToShare(request.content, request.timestamp) }") ||
                source.contains("{ request -> navigateToShare(request.content, request.timestamp) }") shouldBe true
        }
        withClue("$path must keep action usage and ordering callbacks on the handler") {
            (source.contains(actionUsageCallback) && source.contains(actionOrderCallback)) shouldBe true
        }
    }

    private fun appSource(relativePath: String): File =
        appSourceRoot.resolve(relativePath).also { file ->
            withClue("Expected app source file exists: ${file.path}") {
                file.isFile shouldBe true
            }
        }

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
}
