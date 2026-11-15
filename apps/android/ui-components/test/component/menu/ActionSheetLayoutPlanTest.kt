package com.lomo.ui.component.menu

import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableList

/*
 * Behavior Contract:
 * - Unit under test: planActionSheet layout policy for the shared action sheet.
 * - Owning layer: ui-components presentation.
 * - Priority tier: P1.
 * - Capability: partition presentation actions into the top tile row (at most four tiles, which
 *   may carry nested subActions) and the aligned capsule tier.
 *
 * Scenarios:
 * - Given tile and capsule actions in mixed declaration order, when planned, then tiles and
 *   capsules each keep their declaration order.
 * - Given up to four tile actions where one carries subActions, when planned, then all four tiles
 *   and nested subActions are preserved cleanly.
 * - Given capsule actions only, when planned, then only the capsule tier is populated.
 * - Given five tile actions, when planned, then the plan is rejected instead of squeezing tiles or
 *   reintroducing horizontal scrolling.
 *
 * Observable outcomes: per-section label sequences, preserved subActions, and the
 * IllegalArgumentException message.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=ui-components --include-classes='*ActionSheetLayoutPlanTest'`
 *   fails while MaxTileCount is 3, ActionItemUi lacks subActions, and ActionItemPlacement.Row exists.
 * - GREEN: same command after updating ActionSheetTokens, ActionItemUi, and planActionSheet.
 *
 * Excludes: Compose rendering, haptics, click dispatch and app-owned action semantics.
 *
 * Test Change Justification:
 * - Reason category: redesign of memo menu from 3 giant tiles + flat vertical list rows into a
 *   compact 4-tile top grid (with share popup sub-actions) + aligned bottom capsules.
 * - Old behavior/assertion being replaced: 3-tile limit, ActionItemPlacement.Row partitioning, and
 *   destructiveRows list separation.
 * - Why old assertion is no longer correct: ActionItemPlacement.Row and the flat list section are
 *   removed; the top row now supports 4 compact tiles while secondary/destructive actions share the
 *   aligned capsule row beneath them.
 * - Coverage preserved by: declaration-order preservation per tier, capsule-only layout, tile upper
 *   bound enforcement (rejecting 5 tiles), and nested subAction retention.
 * - Why this is not fitting the test to the implementation: assertions lock the redesigned product
 *   capacity and layout partition before production code changes.
 */
class ActionSheetLayoutPlanTest : UiComponentsFunSpec() {
    init {
        test("given mixed tiles and capsules in declaration order when planned then each section keeps order") {
            val subActions =
                listOf(
                    action("image", ActionItemPlacement.Capsule),
                    action("text", ActionItemPlacement.Capsule),
                )
            val plan =
                planActionSheet(
                    listOf(
                        action("edit", ActionItemPlacement.Tile),
                        action("history", ActionItemPlacement.Capsule),
                        action("copy", ActionItemPlacement.Tile),
                        action("pin", ActionItemPlacement.Tile),
                        action("delete", ActionItemPlacement.Capsule, isDestructive = true),
                        action("share", ActionItemPlacement.Tile, subActions = subActions),
                    ),
                )

            assertSoftly(plan) {
                tiles.labels() shouldBe listOf("edit", "copy", "pin", "share")
                tiles.last().subActions.labels() shouldBe listOf("image", "text")
                capsules.labels() shouldBe listOf("history", "delete")
            }
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
                tiles.shouldBeEmpty()
                capsules.labels() shouldBe listOf("restore", "purge")
            }
        }

        test("given five tile actions when planned then the plan is rejected") {
            val error =
                shouldThrow<IllegalArgumentException> {
                    planActionSheet(
                        listOf("a", "b", "c", "d", "e").map { label -> action(label, ActionItemPlacement.Tile) },
                    )
                }

            error.message shouldBe "Action sheet supports at most 4 tiles, got 5: a, b, c, d, e"
        }
    }

    private fun List<ActionItemUi>.labels(): List<String> = map(ActionItemUi::label)

    private fun action(
        label: String,
        placement: ActionItemPlacement,
        isDestructive: Boolean = false,
        subActions: List<ActionItemUi> = emptyList(),
    ): ActionItemUi = ActionItemUi(
        icon = LomoIcons.ContentCopy,
        label = label,
        onClick = {},
        isDestructive = isDestructive,
        placement = placement,
        subActions = subActions.toImmutableList(),
    )
}
