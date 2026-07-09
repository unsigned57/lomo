// architectural-boundary-check
package com.lomo.ui.component.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * - Unit under test: generic action sheet presentation helpers and menu boundary.
 * - Owning layer: ui-components presentation.
 * - Priority tier: P1.
 * - Capability: keep the reusable action sheet keyed by opaque presentation strings
 *   and free of feature-owned menu identities or compatibility accessors.
 *
 * Scenarios:
 * - Given presentation actions and a preferred key order, when actions are sorted, then known
 *   keys are ranked once and unranked or unkeyed actions retain fallback order.
 * - Given duplicate non-null stable keys, when actions are sorted or rendered, then validation
 *   rejects the collision instead of silently dropping one action.
 * - Given unkeyed actions and caller-supplied stable keys, when render identities are derived,
 *   then generated keys remain unique and avoid caller keys.
 * - Given shared menu source files, when the boundary test scans them, then feature-owned menu
 *   identities, business callback names, and compatibility payload accessors are absent.
 *
 * Observable outcomes:
 * - Ordered key/label sequences.
 * - Clear duplicate stable-key validation failure.
 * - Unique render keys for unkeyed and mixed generated/stable key sets.
 * - Empty offender list for the menu boundary scan.
 *
 * TDD proof:
 * - RED: the parent acceptance audit failed because shared menu sources retained feature-owned
 *   action identities, business callbacks, and a compatibility payload accessor.
 *
 * Excludes:
 * - Compose rendering, drag physics, edge animations, and app-layer action semantics.
 */
class ActionSheetPresentationOrderTest : UiComponentsFunSpec() {
    private val menuSourceRoot =
        resolveModuleRoot("ui-components").resolve("src/component/menu")

    init {
        test("given preferred presentation keys when actions are sorted then keyed order is applied once") {
            val actions =
                listOf(
                    action(key = "alpha", label = "Alpha"),
                    action(key = "beta", label = "Beta"),
                    action(key = "gamma", label = "Gamma"),
                ).toImmutableList()

            val sorted =
                sortActionItemUiByPreferredKeys(
                    actions = actions,
                    preferredKeys = listOf("beta", "alpha", "beta"),
                )

            sorted.map(ActionItemUi::key) shouldBe listOf("beta", "alpha", "gamma")
        }

        test("given empty preferred presentation keys when actions are sorted then fallback order is preserved") {
            val actions =
                listOf(
                    action(key = "alpha", label = "Alpha"),
                    action(key = "beta", label = "Beta"),
                ).toImmutableList()

            val sorted =
                sortActionItemUiByPreferredKeys(
                    actions = actions,
                    preferredKeys = emptyList(),
                )

            sorted.map(ActionItemUi::key) shouldBe listOf("alpha", "beta")
        }

        test("given unkeyed actions when keyed actions are ranked then unkeyed actions keep fallback positions") {
            val actions =
                listOf(
                    action(key = "alpha", label = "Alpha"),
                    action(key = null, label = "Custom A"),
                    action(key = null, label = "Custom B"),
                    action(key = "gamma", label = "Gamma"),
                ).toImmutableList()

            val sorted =
                sortActionItemUiByPreferredKeys(
                    actions = actions,
                    preferredKeys = listOf("gamma"),
                )

            sorted.map(ActionItemUi::label) shouldBe listOf("Gamma", "Alpha", "Custom A", "Custom B")
        }

        test("given duplicate stable presentation keys when sorting then validation rejects the surface") {
            val actions =
                listOf(
                    action(key = "share", label = "Share image"),
                    action(key = "share", label = "Share text"),
                    action(key = null, label = "Presentation only"),
                ).toImmutableList()

            val error =
                shouldThrow<IllegalArgumentException> {
                    sortActionItemUiByPreferredKeys(
                        actions = actions,
                        preferredKeys = listOf("share"),
                    )
                }

            error.message shouldBe "ActionItemUi stable keys must be unique: share"
        }

        test("given unkeyed actions with duplicate labels when rendered then generated keys are unique") {
            val actions =
                listOf(
                    action(key = null, label = "Share"),
                    action(key = null, label = "Share"),
                ).toImmutableList()

            val renderEntries = toActionItemRenderEntries(actions)

            renderEntries.map { it.action.label } shouldBe listOf("Share", "Share")
            renderEntries.map(ActionItemRenderEntry::renderKey) shouldBe listOf("no-key-0", "no-key-1")
        }

        test("given generated-looking caller key when rendered then generated keys avoid collisions") {
            val actions =
                listOf(
                    action(key = null, label = "Presentation only"),
                    action(key = "no-key-0", label = "Caller stable"),
                ).toImmutableList()

            val renderEntries = toActionItemRenderEntries(actions)

            renderEntries.map { it.action.label } shouldBe listOf("Presentation only", "Caller stable")
            renderEntries.map(ActionItemRenderEntry::renderKey) shouldBe listOf("no-key-1", "no-key-0")
            renderEntries.map(ActionItemRenderEntry::renderKey).distinct().size shouldBe 2
        }

        test("given shared menu sources when scanned then feature-owned menu surface is absent") {
            val offenders =
                menuSourceRoot
                    .walkTopDown()
                    .filter { file -> file.isFile && file.extension == "kt" }
                    .flatMap { file ->
                        file
                            .readLines()
                            .withIndex()
                            .filter { (_, line) -> forbiddenBoundaryTerms().any(line::contains) }
                            .map { (index, line) -> "${file.relativeTo(menuSourceRoot)}:${index + 1}:$line" }
                    }
                    .toList()

            withClue("ui-components menu must expose only generic presentation actions. Offenders: $offenders") {
                offenders shouldBe emptyList()
            }
        }
    }

    private fun action(
        key: String?,
        label: String,
    ): ActionItemUi =
        ActionItemUi(
            key = key,
            icon = Icons.Outlined.ContentCopy,
            label = label,
            onClick = {},
        )

    private fun forbiddenBoundaryTerms(): List<String> =
        listOf(
            "Memo" + "Action" + "Id",
            "Memo" + "Action" + "Sheet" + "Action",
            "Memo" + "Action" + "Haptic",
            "LAN" + "_" + "SHARE",
            "P" + "IN",
            "J" + "UMP",
            "HIST" + "ORY",
            "version" + "-" + "history",
            "on" + "Lan" + "Share",
            "on" + "Toggle" + "Pin",
            "on" + "History",
            "on" + "Memo" + "Action",
            "on" + "Edit",
            "on" + "Delete",
            "Pay" + "load",
            "with" + "Pay" + "load",
            "memo" + "As",
            "memo" + ": " + "Any?",
            "val " + "memo" + ":",
            "state" + "." + "memo",
        )

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
