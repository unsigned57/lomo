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
 * - Given LAN share and the jump context at those callsites, when the handler builder is inspected,
 *   then payload content/timestamp and the MemoMenuContextAction.Jump context remain wired.
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
 *
 * Production scope: apps/android/app/src/feature/tag/TagFilterScreen.kt
 * Production scope: apps/android/app/src/feature/gallery/GalleryScreen.kt
 * Production scope: apps/android/app/src/navigation/GalleryReelNavigation.kt
 * Production scope: apps/android/app/src/feature/memo/MemoMenuBinder.kt
 *
 * Test Change Justification:
 * - Reason category: product feature removal (usage-based memo menu auto-reorder) and the menu
 *   redesign's required context action.
 * - Old behavior/assertion being replaced: callsites had to wire a MemoActionOrderScopes scope plus
 *   action-usage and action-order callbacks into the handler.
 * - Why old assertion is no longer correct: the reorder feature was deleted, so those scopes and
 *   callbacks no longer exist; non-main surfaces now must provide the jump context action instead.
 * - Coverage preserved by: handler-first construction, handler passing and LAN payload wiring are
 *   still asserted; the jump context replaces the removed callback assertion at each callsite.
 * - Why this is not fitting the test to the implementation: the replacement assertion encodes the
 *   product rule that every non-main memo surface offers jump as its third tile.
 */
package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

class MemoMenuHandlerSurfaceContractTest : AppFunSpec() {
    private val appSourceRoot = resolveModuleRoot("app").resolve("src")

    init {
        test("given app memo host sources when inspected then only handler-first app APIs remain") {
            val binderSource = appSource("feature/memo/MemoMenuBinder.kt").readText()
            val hostSource = appSource("feature/memo/MemoInteractionHost.kt").readText()

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
                path = "feature/tag/TagFilterScreen.kt",
                hostCall = "MemoInteractionHost(",
                passParameter = "menuCommandHandler = memoMenuCommandHandler",
            )
            assertHandlerFirstCallsite(
                path = "feature/gallery/GalleryScreen.kt",
                hostCall = "MemoMenuBinder(",
                passParameter = "commandHandler = memoMenuCommandHandler",
            )
            assertHandlerFirstCallsite(
                path = "navigation/GalleryReelNavigation.kt",
                hostCall = "MemoMenuBinder(",
                passParameter = "commandHandler = memoMenuCommandHandler",
            )
        }
    }

    private fun assertOnlyHandlerFirstComposable(source: String, functionName: String, handlerParameter: String) {
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
    }

    private fun assertHandlerFirstCallsite(path: String, hostCall: String, passParameter: String) {
        val source = appSource(path).readText()
        val handlerIndex = source.indexOf("rememberMemoMenuCommandHandler(")
        val hostIndex = source.indexOf(hostCall)

        withClue("$path must build MemoMenuCommandHandler before invoking $hostCall") {
            (handlerIndex >= 0 && hostIndex > handlerIndex) shouldBe true
        }
        withClue("$path must pass the handler into $hostCall") {
            source.contains(passParameter) shouldBe true
        }
        withClue("$path must route LAN share payload through MemoMenuLanShareRequest") {
            source.contains("{ request -> onNavigateToShare(request.content, request.timestamp) }") ||
                source.contains("{ request -> navigateToShare(request.content, request.timestamp) }") shouldBe true
        }
        withClue("$path must offer jump as the memo menu context action") {
            source.contains("MemoMenuContextAction.Jump {") shouldBe true
        }
    }

    private fun appSource(relativePath: String): File = appSourceRoot.resolve(relativePath).also { file ->
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
                dir.name == moduleName && dir.resolve("module.yaml").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
