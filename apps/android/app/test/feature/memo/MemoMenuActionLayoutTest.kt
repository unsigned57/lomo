package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.menu.ActionItemPlacement
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: memoMenuActionLayout.
 * - Owning layer: app/feature/memo menu composition.
 * - Priority tier: P1.
 * - Capability: every memo menu shows four compact tiles on top (edit, copy, screen context
 *   action, share menu carrying share-image/share-text/optional-LAN sub-slots) followed by aligned
 *   bottom capsules for optional version history and delete.
 *
 * Scenarios:
 * - Given the main-list pin context with LAN share and version history, when laid out, then the
 *   tiles are edit/copy/pin/share-menu (with 3 sub-slots) and the capsules are history/delete.
 * - Given a jump context without LAN share or version history, when laid out, then the share tile
 *   carries only share-image and share-text sub-slots and the capsule tier contains only delete.
 *
 * Observable outcomes: ordered MemoMenuActionSlot lists including nested subSlots.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=app --include-classes='*MemoMenuActionLayoutTest'` fails
 *   until MemoActionId.SHARE_MENU, MemoMenuActionSlot.subSlots, and the 4-tile + capsule layout
 *   replace the old 3-tile + 5-row layout.
 * - GREEN: same command after memoMenuActionLayout derives the 4-tile + capsule structure.
 *
 * Excludes: Compose rendering, labels, icons, click dispatch (MemoMenuCommandHandlerTest).
 *
 * Test Change Justification:
 * - Reason category: redesign of memo menu to restore 1.6.2-inspired 4-tile top bar, merge the
 *   3 share options under a single Share popup tile, and place history + delete as bottom capsules.
 * - Old behavior/assertion being replaced: 3-tile layout (edit/copy/context) followed by 3–5
 *   ActionItemPlacement.Row list items.
 * - Why old assertion is no longer correct: SHARE_IMAGE, SHARE_TEXT, and LAN_SHARE are now
 *   grouped under SHARE_MENU instead of separate flat rows, and HISTORY/DELETE are capsules.
 * - Coverage preserved by: still asserts complete ordered placement for both pin and jump
 *   contexts and full conditional coverage of optional LAN share and version history capabilities.
 * - Why this is not fitting the test to the implementation: encodes the exact user-specified
 *   menu slot contract before modifying MemoActionId.kt.
 */
class MemoMenuActionLayoutTest : AppFunSpec() {
    init {
        test(
            "given pin context with all capabilities then four tiles have share sub-slots and capsules pair history",
        ) {
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
                    MemoMenuActionSlot(
                        id = MemoActionId.SHARE_MENU,
                        placement = ActionItemPlacement.Tile,
                        subSlots =
                        listOf(
                            MemoActionId.SHARE_IMAGE,
                            MemoActionId.SHARE_TEXT,
                            MemoActionId.LAN_SHARE,
                        ),
                    ),
                    MemoMenuActionSlot(MemoActionId.HISTORY, ActionItemPlacement.Capsule),
                    MemoMenuActionSlot(MemoActionId.DELETE, ActionItemPlacement.Capsule),
                )
        }

        test(
            "given jump context without optional capabilities then share omits LAN and capsule shows delete",
        ) {
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
                    MemoMenuActionSlot(
                        id = MemoActionId.SHARE_MENU,
                        placement = ActionItemPlacement.Tile,
                        subSlots = listOf(MemoActionId.SHARE_IMAGE, MemoActionId.SHARE_TEXT),
                    ),
                    MemoMenuActionSlot(MemoActionId.DELETE, ActionItemPlacement.Capsule),
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
