// adversarial-audit: WidgetGlanceSnapshotStore writes a typed UNAVAILABLE generation
// whenever the mount does not admit projection reads (engine Opening, no workspace selected,
// recovery), and LomoWidget.provideGlance maps it to WidgetSnapshot.Ready. But WidgetContent
// picks WidgetEmptyState ("No memos yet") purely on items.isEmpty() and only consults
// availability for the REDACTED row text — so a persisted UNAVAILABLE snapshot renders the
// empty-vault message instead of widget_snapshot_unavailable, collapsing "unavailable" into
// "empty vault" exactly as the package invariant forbids ("不可用不等于空库"; the enum's own doc
// says UNAVAILABLE must "never render as empty").

package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: LomoWidget rendering of a persisted UNAVAILABLE snapshot generation.
 * - Owning layer: app (architecture boundary probe over widget source).
 * - Priority tier: P0.
 * - Capability: a persisted UNAVAILABLE snapshot renders its own unavailable text and never
 *   falls through to the empty-vault message.
 *
 * Scenarios:
 * - Given a persisted UNAVAILABLE generation, when the widget renders, then it does not fall
 *   through to the empty-vault text.
 *
 * Observable outcomes: widget source renders the unavailable affordance for that state.
 *
 * TDD proof:
 * - RED while WidgetContent picked WidgetEmptyState purely on items.isEmpty(); GREEN once
 *   availability drives the chrome.
 *
 * Excludes:
 * - Glance pixel rendering and snapshot persistence mechanics.
 */
class WidgetSnapshotUnavailableRenderingContractTest : AppFunSpec() {
    init {
        test("given a persisted UNAVAILABLE generation when the widget renders then it must not fall through to the empty-vault text") {
            val source =
                resolveModuleRoot("app")
                    .resolve("src/widget/LomoWidget.kt")
                    .readText()

            withClue(
                "LomoWidget must branch on WidgetSnapshotAvailability.UNAVAILABLE (either in " +
                    "provideGlance mapping or in WidgetContent) so a mount that does not admit " +
                    "reads renders widget_snapshot_unavailable, never the 'No memos yet' " +
                    "empty-vault state reserved for a verified empty READY snapshot",
            ) {
                source.contains("WidgetSnapshotAvailability.UNAVAILABLE") shouldBe true
            }
        }
    }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots
                .filterNotNull()
                .firstOrNull { dir ->
                    dir.name == moduleName && dir.resolve("module.yaml").exists()
                },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
