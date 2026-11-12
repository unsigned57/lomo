package com.lomo.ui.component.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: planActionSheet layout policy for the shared action sheet.
 * - Owning layer: ui-components presentation.
 * - Priority tier: P1.
 * - Capability: partition presentation actions into the capsule tier, the tile row (at most three
 *   tiles) and the list, with destructive list rows always rendered last behind a divider.
 *
 * Scenarios:
 * - Given tile and row actions in mixed declaration order, when planned, then tiles and rows each
 *   keep their declaration order.
 * - Given a destructive row declared before regular rows, when planned, then it is moved to the
 *   destructive section after every regular row.
 * - Given capsule actions only, when planned, then only the capsule tier is populated.
 * - Given four tile actions, when planned, then the plan is rejected instead of squeezing tiles.
 *
 * Observable outcomes: per-section label sequences and the IllegalArgumentException message.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=ui-components --include-classes='*ActionSheetLayoutPlanTest'`
 *   failed against a placeholder that returned every action as a regular row, because the
 *   tile/destructive partition and the tile limit did not exist.
 * - GREEN: same command after planActionSheet partitions and validates actions.
 *
 * Excludes: Compose rendering, haptics, click dispatch and app-owned action semantics.
 */
class ActionSheetLayoutPlanTest : UiComponentsFunSpec() {
    init {
        test("given mixed tiles and rows when planned then each section keeps declaration order") {
            val plan =
                planActionSheet(
                    listOf(
                        action("edit", ActionItemPlacement.Tile),
                        action("share", ActionItemPlacement.Row),
                        action("copy", ActionItemPlacement.Tile),
                        action("history", ActionItemPlacement.Row),
                        action("pin", ActionItemPlacement.Tile),
                    ),
                )

            assertSoftly(plan) {
                capsules.shouldBeEmpty()
                tiles.labels() shouldBe listOf("edit", "copy", "pin")
                rows.labels() shouldBe listOf("share", "history")
                destructiveRows.shouldBeEmpty()
            }
        }

        test("given a destructive row declared first when planned then it follows every regular row") {
            val plan =
                planActionSheet(
                    listOf(
                        action("delete", ActionItemPlacement.Row, isDestructive = true),
                        action("share", ActionItemPlacement.Row),
                        action("history", ActionItemPlacement.Row),
                    ),
                )

            plan.rows.labels() shouldBe listOf("share", "history")
            plan.destructiveRows.labels() shouldBe listOf("delete")
        }

        test("given capsule actions only when planned then only the capsule tier is populated") {
            val plan =
                planActionSheet(
                    listOf(
                        action("restore", ActionItemPlacement.Capsule),
                        action("purge", ActionItemPlacement.Capsule, isDestructive = true),
                    ),
                )

            assertSoftly(plan) {
                capsules.labels() shouldBe listOf("restore", "purge")
                tiles.shouldBeEmpty()
                rows.shouldBeEmpty()
                destructiveRows.shouldBeEmpty()
            }
        }

        test("given four tile actions when planned then the plan is rejected") {
            val error =
                shouldThrow<IllegalArgumentException> {
                    planActionSheet(
                        listOf("a", "b", "c", "d").map { label -> action(label, ActionItemPlacement.Tile) },
                    )
                }

            error.message shouldBe "Action sheet supports at most 3 tiles, got 4: a, b, c, d"
        }
    }

    private fun List<ActionItemUi>.labels(): List<String> = map(ActionItemUi::label)

    private fun action(label: String, placement: ActionItemPlacement, isDestructive: Boolean = false): ActionItemUi =
        ActionItemUi(
            icon = Icons.Outlined.ContentCopy,
            label = label,
            onClick = {},
            isDestructive = isDestructive,
            placement = placement,
        )
}
