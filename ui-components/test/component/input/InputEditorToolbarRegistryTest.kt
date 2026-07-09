// architectural-boundary-check
package com.lomo.ui.component.input

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf

/*
 * Behavior Contract:
 * - Unit under test: InputToolbarRegistry.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P1.
 * - Capability: present editor toolbar actions from app-provided metadata while ui-components owns
 *   only ordering, deduplication, and generic command dispatch slots.
 *
 * Scenarios:
 * - Given a persisted toolbar order with duplicates and unknown ids, when registry actions are resolved,
 *   then app-provided actions are deduplicated, unknown ids are ignored, and missing actions are appended.
 * - Given action metadata carries enablement, tint, icon, label, and command values, when registry
 *   actions are resolved, then the same metadata is preserved without ui-components naming feature tools.
 * - Given input component sources are inspected, when ui-components exposes editor input APIs, then
 *   memo-specific backfill, reminder, attach-location, and geo concepts are absent.
 *
 * Observable outcomes:
 * - ordered action ids, normalized persisted ids, enabled flags, tint role, and opaque command id.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-classes='com.lomo.ui.component.input.InputEditorToolbarRegistryTest'`
 *   fails before this repair because ui-components still exposes Backfill/Reminder toolbar ids, AttachLocation
 *   and Backfill commands, attachedGeoLocation/backfillBadgeText state, and cd_backfill_memo binding.
 *
 * Excludes:
 * - Compose rendering, drag gesture mechanics, Android permission launcher behavior.
 */
class InputEditorToolbarRegistryTest : UiComponentsFunSpec() {
    init {
        test("given persisted order when resolved then registry returns deduplicated app provided actions") {
            val camera = contractTool("camera")
            val image = contractTool("image")
            val custom = contractTool("memo-special")
            val registry =
                InputToolbarRegistry.create(
                    InputToolbarRegistryState(
                        tools = persistentListOf(camera, image, custom),
                    ),
                )

            val tools =
                registry.resolveTools(
                    persistedOrder = listOf("memo-special", "camera", "unknown", "memo-special", "record"),
                )

            tools.map { it.id } shouldContainExactly
                listOf(
                    InputToolbarActionId("memo-special"),
                    InputToolbarActionId("camera"),
                    InputToolbarActionId("image"),
                )
            registry.normalizePersistedOrder(listOf("memo-special", "camera", "unknown", "memo-special")) shouldContainExactly
                listOf(InputToolbarActionId("memo-special"), InputToolbarActionId("camera"))
        }

        test("given action metadata when resolved then registry preserves app owned command slots") {
            val actionId = InputToolbarActionId("app-owned-action")
            val tool =
                contractTool(actionId.persistedId).copy(
                    enabled = false,
                    tintRole = InputToolbarToolTintRole.Highlight,
                    command = InputEditorCommand.Action(actionId),
                )
            val registry =
                InputToolbarRegistry.create(
                    InputToolbarRegistryState(
                        tools = persistentListOf(tool),
                    ),
                )

            val tools = registry.resolveTools(persistedOrder = emptyList()).associateBy { it.id }

            tools.getValue(actionId).enabled shouldBe false
            tools.getValue(actionId).tintRole shouldBe InputToolbarToolTintRole.Highlight
            tools.getValue(actionId).command shouldBe InputEditorCommand.Action(actionId)
        }

        test("given ui input sources when inspected then memo toolbar concepts are absent") {
            val sourceRoot = java.io.File("src/component/input")
            val inputSources = sourceRoot.walkTopDown().filter { file -> file.extension == "kt" }.toList()
            val combinedSource = inputSources.joinToString(separator = "\n") { file -> file.readText() }

            listOf(
                "Backfill",
                "Reminder",
                "AttachLocation",
                "attachedGeoLocation",
                "backfillBadgeText",
                "isBackfillEnabled",
                "cd_backfill_memo",
            ).forEach { forbidden ->
                combinedSource.contains(forbidden) shouldBe false
            }
        }
    }

    private fun contractTool(id: String): InputToolbarTool =
        InputToolbarTool(
            id = InputToolbarActionId(id),
            icon = Icons.Rounded.Image,
            contentDescriptionRes = android.R.string.ok,
            command = InputEditorCommand.Action(InputToolbarActionId(id)),
            enabled = true,
        )
}
