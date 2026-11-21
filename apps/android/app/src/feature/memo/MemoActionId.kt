package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.component.menu.ActionItemHaptic
import com.lomo.ui.component.menu.ActionItemPlacement
import com.lomo.ui.component.menu.ActionItemUi
import com.lomo.ui.component.menu.MemoMenuActionLifecycle
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

enum class MemoActionId {
    EDIT,
    COPY,
    PIN,
    JUMP,
    SHARE_MENU,
    SHARE_IMAGE,
    SHARE_TEXT,
    LAN_SHARE,
    HISTORY,
    DELETE,
}

internal data class MemoMenuActionSlot(
    val id: MemoActionId,
    val placement: ActionItemPlacement,
    val subSlots: List<MemoActionId> = emptyList(),
)

/**
 * Fixed memo menu layout: four compact top tiles (edit, copy, screen context action, and grouped
 * share actions) followed by aligned bottom capsules for optional history and delete.
 */
internal fun memoMenuActionLayout(handler: MemoMenuCommandHandler): List<MemoMenuActionSlot> = buildList {
    val contextActionId =
        when (handler.contextAction) {
            is MemoMenuContextAction.TogglePin -> MemoActionId.PIN
            is MemoMenuContextAction.Jump -> MemoActionId.JUMP
        }
    val shareSubSlots =
        listOfNotNull(
            MemoActionId.SHARE_IMAGE,
            MemoActionId.SHARE_TEXT,
            MemoActionId.LAN_SHARE.takeIf { handler.hasLanShare },
        )
    add(MemoMenuActionSlot(MemoActionId.EDIT, ActionItemPlacement.Tile))
    add(MemoMenuActionSlot(MemoActionId.COPY, ActionItemPlacement.Tile))
    add(MemoMenuActionSlot(contextActionId, ActionItemPlacement.Tile))
    add(MemoMenuActionSlot(MemoActionId.SHARE_MENU, ActionItemPlacement.Tile, subSlots = shareSubSlots))
    if (handler.hasVersionHistory) {
        add(MemoMenuActionSlot(MemoActionId.HISTORY, ActionItemPlacement.Capsule))
    }
    add(MemoMenuActionSlot(MemoActionId.DELETE, ActionItemPlacement.Capsule))
}

@Composable
internal fun rememberMemoMenuActions(
    selection: MemoMenuSelection,
    lifecycle: MemoMenuActionLifecycle,
    commandHandler: MemoMenuCommandHandler,
): ImmutableList<ActionItemUi> {
    val isPinned = selection.memo.isPinned
    val labels = rememberMemoMenuActionLabels(isPinned)
    return remember(selection, lifecycle, commandHandler, labels) {
        memoMenuActionLayout(commandHandler)
            .map { slot ->
                memoMenuActionItem(
                    slot = slot,
                    selection = selection,
                    labels = labels,
                    lifecycle = lifecycle,
                    commandHandler = commandHandler,
                )
            }.toImmutableList()
    }
}

@Composable
@ReadOnlyComposable
private fun rememberMemoMenuActionLabels(isPinned: Boolean): MemoMenuActionLabels = MemoMenuActionLabels(
    copy = stringResource(R.string.action_copy),
    shareMenu = stringResource(R.string.action_share_menu),
    shareImage = stringResource(R.string.action_share),
    shareText = stringResource(R.string.action_share_text),
    lanShare = stringResource(R.string.action_lan_share),
    pin = stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin),
    jump = stringResource(R.string.action_jump),
    history = stringResource(R.string.action_history),
    edit = stringResource(R.string.action_edit),
    delete = stringResource(R.string.action_delete),
    clipboardLabel = stringResource(R.string.clipboard_label_memo),
)

private fun memoMenuActionItem(
    slot: MemoMenuActionSlot,
    selection: MemoMenuSelection,
    labels: MemoMenuActionLabels,
    lifecycle: MemoMenuActionLifecycle,
    commandHandler: MemoMenuCommandHandler,
): ActionItemUi {
    val subActions =
        slot.subSlots
            .map { subId ->
                memoMenuLeafItem(
                    actionId = subId,
                    placement = ActionItemPlacement.Capsule,
                    selection = selection,
                    labels = labels,
                    lifecycle = lifecycle,
                    commandHandler = commandHandler,
                )
            }.toImmutableList()
    return memoMenuLeafItem(
        actionId = slot.id,
        placement = slot.placement,
        selection = selection,
        labels = labels,
        lifecycle = lifecycle,
        commandHandler = commandHandler,
        subActions = subActions,
    )
}

private fun memoMenuLeafItem(
    actionId: MemoActionId,
    placement: ActionItemPlacement,
    selection: MemoMenuSelection,
    labels: MemoMenuActionLabels,
    lifecycle: MemoMenuActionLifecycle,
    commandHandler: MemoMenuCommandHandler,
    subActions: ImmutableList<ActionItemUi> = persistentListOf(),
): ActionItemUi {
    fun buildItem(
        icon: LomoIconSpec,
        label: String,
        onClick: () -> Unit,
        isDestructive: Boolean = false,
        isHighlighted: Boolean = false,
        haptic: ActionItemHaptic = ActionItemHaptic.MEDIUM,
    ) = ActionItemUi(
        icon = icon,
        label = label,
        onClick = onClick,
        placement = placement,
        benchmarkTag = benchmarkMemoActionAnchor(actionId),
        isDestructive = isDestructive,
        isHighlighted = isHighlighted,
        dismissAfterClick = false,
        haptic = haptic,
        subActions = subActions,
    )

    return when (actionId) {
        MemoActionId.EDIT ->
            buildItem(
                LomoIcons.EditSquare,
                labels.edit,
                {
                    lifecycle.hideAndConsume {
                        commandHandler.edit(selection)
                    }
                },
            )

        MemoActionId.COPY ->
            buildItem(
                LomoIcons.ContentCopy,
                labels.copy,
                { lifecycle.hideAndConsume { commandHandler.copy(selection, labels.clipboardLabel) } },
            )

        MemoActionId.PIN ->
            buildItem(
                if (selection.memo.isPinned) LomoIcons.KeepFilled else LomoIcons.Keep,
                labels.pin,
                { lifecycle.consume { commandHandler.runContextAction(selection) } },
                isHighlighted = selection.memo.isPinned,
            )

        MemoActionId.JUMP ->
            buildItem(
                LomoIcons.ArrowOutward,
                labels.jump,
                { lifecycle.consume { commandHandler.runContextAction(selection) } },
            )

        MemoActionId.SHARE_MENU ->
            buildItem(LomoIcons.Share, labels.shareMenu, {})

        MemoActionId.SHARE_IMAGE ->
            buildItem(
                LomoIcons.Image,
                labels.shareImage,
                {
                    lifecycle.consume {
                        commandHandler.shareCard(selection)
                    }
                },
            )

        MemoActionId.SHARE_TEXT ->
            buildItem(
                LomoIcons.Article,
                labels.shareText,
                {
                    lifecycle.consume {
                        commandHandler.shareText(selection)
                    }
                },
            )

        MemoActionId.LAN_SHARE ->
            buildItem(
                LomoIcons.Devices,
                labels.lanShare,
                {
                    lifecycle.consume {
                        commandHandler.lanShare(selection)
                    }
                },
            )

        MemoActionId.HISTORY ->
            buildItem(
                LomoIcons.History,
                labels.history,
                {
                    lifecycle.consume {
                        commandHandler.versionHistory(selection)
                    }
                },
            )

        MemoActionId.DELETE ->
            buildItem(
                LomoIcons.Delete,
                labels.delete,
                {
                    lifecycle.hideAndConsume {
                        commandHandler.delete(selection)
                    }
                },
                isDestructive = true,
                haptic = ActionItemHaptic.MEDIUM,
            )
    }
}

private fun benchmarkMemoActionAnchor(actionId: MemoActionId): String? = when (actionId) {
    MemoActionId.COPY -> BenchmarkAnchorContract.MEMO_ACTION_COPY
    MemoActionId.SHARE_MENU -> BenchmarkAnchorContract.MEMO_ACTION_SHARE_MENU
    MemoActionId.SHARE_IMAGE -> BenchmarkAnchorContract.MEMO_ACTION_SHARE
    MemoActionId.PIN -> BenchmarkAnchorContract.MEMO_ACTION_PIN
    MemoActionId.HISTORY -> BenchmarkAnchorContract.MEMO_ACTION_HISTORY
    MemoActionId.EDIT -> BenchmarkAnchorContract.MEMO_ACTION_EDIT
    MemoActionId.DELETE -> BenchmarkAnchorContract.MEMO_ACTION_DELETE
    MemoActionId.LAN_SHARE -> BenchmarkAnchorContract.MEMO_ACTION_LAN_SHARE
    MemoActionId.SHARE_TEXT, MemoActionId.JUMP -> null
}

private data class MemoMenuActionLabels(
    val copy: String,
    val shareMenu: String,
    val shareImage: String,
    val shareText: String,
    val lanShare: String,
    val pin: String,
    val jump: String,
    val history: String,
    val edit: String,
    val delete: String,
    val clipboardLabel: String,
)
