package com.lomo.app.feature.memo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.component.menu.ActionItemHaptic
import com.lomo.ui.component.menu.ActionItemPlacement
import com.lomo.ui.component.menu.ActionItemUi
import com.lomo.ui.component.menu.MemoMenuActionLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

enum class MemoActionId {
    EDIT,
    COPY,
    PIN,
    JUMP,
    SHARE_IMAGE,
    SHARE_TEXT,
    LAN_SHARE,
    HISTORY,
    DELETE,
}

internal data class MemoMenuActionSlot(val id: MemoActionId, val placement: ActionItemPlacement)

/** Fixed memo menu layout: edit, copy and the screen's context action as tiles, then the list. */
internal fun memoMenuActionLayout(handler: MemoMenuCommandHandler): List<MemoMenuActionSlot> = buildList {
    val contextActionId =
        when (handler.contextAction) {
            is MemoMenuContextAction.TogglePin -> MemoActionId.PIN
            is MemoMenuContextAction.Jump -> MemoActionId.JUMP
        }
    listOf(MemoActionId.EDIT, MemoActionId.COPY, contextActionId)
        .forEach { id -> add(MemoMenuActionSlot(id, ActionItemPlacement.Tile)) }
    listOfNotNull(
        MemoActionId.SHARE_IMAGE,
        MemoActionId.SHARE_TEXT,
        MemoActionId.LAN_SHARE.takeIf { handler.hasLanShare },
        MemoActionId.HISTORY.takeIf { handler.hasVersionHistory },
        MemoActionId.DELETE,
    ).forEach { id -> add(MemoMenuActionSlot(id, ActionItemPlacement.Row)) }
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
private fun rememberMemoMenuActionLabels(isPinned: Boolean): MemoMenuActionLabels = MemoMenuActionLabels(
    copy = stringResource(R.string.action_copy),
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
    fun item(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        onClick: () -> Unit,
        isDestructive: Boolean = false,
        isHighlighted: Boolean = false,
        haptic: ActionItemHaptic = ActionItemHaptic.MEDIUM,
    ) = ActionItemUi(
        icon = icon,
        label = label,
        onClick = onClick,
        placement = slot.placement,
        benchmarkTag = benchmarkMemoActionAnchor(slot.id),
        isDestructive = isDestructive,
        isHighlighted = isHighlighted,
        dismissAfterClick = false,
        haptic = haptic,
    )
    return when (slot.id) {
        MemoActionId.EDIT ->
            item(Icons.Outlined.Edit, labels.edit, { lifecycle.hideAndConsume { commandHandler.edit(selection) } })

        MemoActionId.COPY ->
            item(
                Icons.Outlined.ContentCopy,
                labels.copy,
                { lifecycle.hideAndConsume { commandHandler.copy(selection, labels.clipboardLabel) } },
            )

        MemoActionId.PIN ->
            item(
                Icons.Outlined.PushPin,
                labels.pin,
                { lifecycle.consume { commandHandler.runContextAction(selection) } },
                isHighlighted = selection.memo.isPinned,
            )

        MemoActionId.JUMP ->
            item(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                labels.jump,
                { lifecycle.consume { commandHandler.runContextAction(selection) } },
            )

        MemoActionId.SHARE_IMAGE ->
            item(Icons.Outlined.Image, labels.shareImage, { lifecycle.consume { commandHandler.shareCard(selection) } })

        MemoActionId.SHARE_TEXT ->
            item(
                Icons.AutoMirrored.Outlined.TextSnippet,
                labels.shareText,
                { lifecycle.consume { commandHandler.shareText(selection) } },
            )

        MemoActionId.LAN_SHARE ->
            item(Icons.Outlined.Wifi, labels.lanShare, { lifecycle.consume { commandHandler.lanShare(selection) } })

        MemoActionId.HISTORY ->
            item(
                Icons.Outlined.History,
                labels.history,
                { lifecycle.consume { commandHandler.versionHistory(selection) } },
            )

        MemoActionId.DELETE ->
            item(
                Icons.Outlined.Delete,
                labels.delete,
                { lifecycle.hideAndConsume { commandHandler.delete(selection) } },
                isDestructive = true,
                haptic = ActionItemHaptic.HEAVY,
            )
    }
}

private fun benchmarkMemoActionAnchor(actionId: MemoActionId): String? = when (actionId) {
    MemoActionId.COPY -> BenchmarkAnchorContract.MEMO_ACTION_COPY
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
