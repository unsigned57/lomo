package com.lomo.ui.component.input

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf

/*
 * Behavior Contract:
 * - Unit under test: InputToolbarRegistry ordering policy.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P1.
 * - Capability: keep toolbar ordering generic by following app-provided action metadata and
 *   persisted action ids.
 *
 * Scenarios:
 * - Given app-provided toolbar metadata, when tools are resolved without persisted order, then
 *   registry preserves the metadata order.
 * - Given persisted order with duplicates and unknown ids, when tools are resolved, then known typed
 *   ids are deduplicated and missing tools are appended by registry order.
 * - Given an action is disabled by app metadata, when tools are resolved, then disabled state is
 *   preserved by the generic registry.
 *
 * Observable outcomes:
 * - typed action ids, relative ordering, filtering, completion, and enabled state.
 *
 * TDD proof:
 * - RED: old string-id helpers and enum-owned default ordering fail after the generic action
 *   metadata migration.
 *
 * Excludes:
 * - icon rendering, drag gestures, Android permission launcher behavior.
 */
class InputEditorToolbarOrderTest : UiComponentsFunSpec() {
    init {
        test("empty persisted order follows app provided toolbar metadata order") {
            val tools = persistentListOf(contractTool("primary"), contractTool("secondary"), contractTool("tertiary"))
            val toolIds =
                InputToolbarRegistry
                    .create(InputToolbarRegistryState(tools = tools))
                    .resolveTools(persistedOrder = emptyList())
                    .map(InputToolbarTool::id)

            toolIds shouldContainExactly
                listOf(
                    InputToolbarActionId("primary"),
                    InputToolbarActionId("secondary"),
                    InputToolbarActionId("tertiary"),
                )
        }

        test("persisted toolbar order is deduplicated and completed with default tools") {
            val tools = persistentListOf(contractTool("primary"), contractTool("secondary"), contractTool("tertiary"))
            val toolIds =
                InputToolbarRegistry
                    .create(InputToolbarRegistryState(tools = tools))
                    .resolveTools(
                        persistedOrder = listOf("tertiary", "primary", "unknown", "tertiary"),
                    ).map(InputToolbarTool::id)

            toolIds shouldContainExactly
                listOf(
                    InputToolbarActionId("tertiary"),
                    InputToolbarActionId("primary"),
                    InputToolbarActionId("secondary"),
                )
        }

        test("action enablement comes from app metadata") {
            val disabledAction =
                InputToolbarRegistry
                    .create(
                        InputToolbarRegistryState(
                            tools = persistentListOf(contractTool("primary").copy(enabled = false)),
                        ),
                    )
                    .resolveTools(persistedOrder = emptyList())
                    .first()

            disabledAction.enabled shouldBe false
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
