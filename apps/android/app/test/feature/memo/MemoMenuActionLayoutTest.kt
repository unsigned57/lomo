package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.menu.ActionItemPlacement
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: memoMenuActionLayout.
 * - Owning layer: app/feature/memo menu composition.
 * - Priority tier: P1.
 * - Capability: every memo menu shows exactly three tiles (edit, copy, then the screen's context
 *   action) followed by a fixed list whose optional rows follow handler capabilities and whose
 *   delete row is always last.
 *
 * Scenarios:
 * - Given the main-list pin context with LAN share and version history, when laid out, then the
 *   tiles are edit/copy/pin and the list is share image, share text, LAN, history, delete.
 * - Given a jump context without LAN share or version history, when laid out, then the tiles are
 *   edit/copy/jump and the list omits the unavailable rows.
 *
 * Observable outcomes: ordered (action id, placement) slot lists.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=app --include-classes='*MemoMenuActionLayoutTest'` failed
 *   against a placeholder layout returning no slots, because the fixed tile/list layout did not exist.
 * - GREEN: same command after memoMenuActionLayout derives the slots from the handler.
 *
 * Excludes: Compose rendering, labels, icons, click dispatch (MemoMenuCommandHandlerTest).
 *
 * Test Change Justification:
 * - Reason category: product feature removal (usage-based memo menu auto-reorder).
 * - Old behavior/assertion being replaced: MemoActionIdTest asserted storage-key parsing and the
 *   default persisted action order.
 * - Why old assertion is no longer correct: action ids are no longer persisted; storage keys and the
 *   default order existed only to rank tiles by usage, which never affected the rendered sheet.
 * - Coverage preserved by: this spec asserts the complete, fixed order and placement of every action.
 * - Why this is not fitting the test to the implementation: the assertions encode the product
 *   layout decided for the redesign, independent of how slots are produced.
 */
class MemoMenuActionLayoutTest : AppFunSpec() {
    init {
        test("given the pin context with every optional capability when laid out then pin is the third tile") {
            val handler =
                handler(
                    contextAction = MemoMenuContextAction.TogglePin { _, _ -> },
                    lanShare = true,
                    versionHistory = true,
                )

            memoMenuActionLayout(handler) shouldBe
                listOf(
                    MemoMenuActionSlot(MemoActionId.EDIT, ActionItemPlacement.Tile),
                    MemoMenuActionSlot(MemoActionId.COPY, ActionItemPlacement.Tile),
                    MemoMenuActionSlot(MemoActionId.PIN, ActionItemPlacement.Tile),
                    MemoMenuActionSlot(MemoActionId.SHARE_IMAGE, ActionItemPlacement.Row),
                    MemoMenuActionSlot(MemoActionId.SHARE_TEXT, ActionItemPlacement.Row),
                    MemoMenuActionSlot(MemoActionId.LAN_SHARE, ActionItemPlacement.Row),
                    MemoMenuActionSlot(MemoActionId.HISTORY, ActionItemPlacement.Row),
                    MemoMenuActionSlot(MemoActionId.DELETE, ActionItemPlacement.Row),
                )
        }

        test("given the jump context without optional capabilities when laid out then unavailable rows are absent") {
            val handler =
                handler(
                    contextAction = MemoMenuContextAction.Jump { },
                    lanShare = false,
                    versionHistory = false,
                )

            memoMenuActionLayout(handler) shouldBe
                listOf(
                    MemoMenuActionSlot(MemoActionId.EDIT, ActionItemPlacement.Tile),
                    MemoMenuActionSlot(MemoActionId.COPY, ActionItemPlacement.Tile),
                    MemoMenuActionSlot(MemoActionId.JUMP, ActionItemPlacement.Tile),
                    MemoMenuActionSlot(MemoActionId.SHARE_IMAGE, ActionItemPlacement.Row),
                    MemoMenuActionSlot(MemoActionId.SHARE_TEXT, ActionItemPlacement.Row),
                    MemoMenuActionSlot(MemoActionId.DELETE, ActionItemPlacement.Row),
                )
        }
    }

    private fun handler(
        contextAction: MemoMenuContextAction,
        lanShare: Boolean,
        versionHistory: Boolean,
    ): MemoMenuCommandHandler = MemoMenuCommandHandler(
        presentationState =
        MemoMenuPresentationState(
            shareCardShowTime = true,
            shareCardShowSignature = false,
            shareCardSignatureText = "",
            customFontPath = null,
        ),
        onEditMemo = {},
        onDeleteMemo = { _, _ -> },
        onShareCard = {},
        onShareText = {},
        onCopyMemo = { _, _ -> },
        contextAction = contextAction,
        onLanShare = if (lanShare) ({ _ -> }) else null,
        onVersionHistory = if (versionHistory) ({ _ -> }) else null,
    )
}
