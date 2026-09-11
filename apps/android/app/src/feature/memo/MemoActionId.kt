package com.lomo.app.feature.memo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.component.menu.ActionItemHaptic
import com.lomo.ui.component.menu.ActionItemUi
import com.lomo.ui.component.menu.MemoMenuActionLifecycle
import com.lomo.ui.component.menu.sortActionItemUiByPreferredKeys
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

enum class MemoActionId(
    val storageKey: String,
) {
    COPY("copy"),
    SHARE_IMAGE("share_image"),
    SHARE_TEXT("share_text"),
    LAN_SHARE("lan_share"),
    PIN("pin"),
    JUMP("jump"),
    HISTORY("history"),
    EDIT("edit"),
    DELETE("delete"),
    ;

    companion object {
        fun fromStorageKey(raw: String): MemoActionId? =
            entries.firstOrNull { actionId -> actionId.storageKey == raw.trim() }
    }
}

internal fun defaultMemoActionOrder(): List<String> = MemoActionId.entries.map(MemoActionId::storageKey)

@Composable
internal fun rememberMemoMenuActions(
    selection: MemoMenuSelection,
    lifecycle: MemoMenuActionLifecycle,
    commandHandler: MemoMenuCommandHandler,
): ImmutableList<ActionItemUi> {
    val isPinned = selection.memo.isPinned
    val labels = rememberMemoMenuActionLabels(isPinned)
    val orderedKeys = commandHandler.presentationState.memoActionOrder
    return remember(selection, lifecycle, commandHandler, labels, orderedKeys, isPinned) {
        sortActionItemUiByPreferredKeys(
            actions =
                defaultMemoMenuActions(
                    selection = selection,
                    labels = labels,
                    lifecycle = lifecycle,
                    commandHandler = commandHandler,
                    isPinned = isPinned,
                ).toImmutableList(),
            preferredKeys = orderedKeys,
        )
    }
}

@Composable
private fun rememberMemoMenuActionLabels(isPinned: Boolean): MemoMenuActionLabels =
    MemoMenuActionLabels(
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

private fun defaultMemoMenuActions(
    selection: MemoMenuSelection,
    labels: MemoMenuActionLabels,
    lifecycle: MemoMenuActionLifecycle,
    commandHandler: MemoMenuCommandHandler,
    isPinned: Boolean,
): List<ActionItemUi> =
    listOfNotNull(
        ActionItemUi(
            key = MemoActionId.COPY.storageKey,
            icon = Icons.Outlined.ContentCopy,
            label = labels.copy,
            onClick = {
                lifecycle.copyTextAndHide(
                    label = labels.clipboardLabel,
                    text = selection.state.content,
                )
            },
            dismissAfterClick = false,
        ),
        ActionItemUi(
            key = MemoActionId.SHARE_IMAGE.storageKey,
            icon = Icons.Outlined.Share,
            label = labels.shareImage,
            onClick = { lifecycle.consume { commandHandler.shareCard(selection) } },
            dismissAfterClick = false,
        ),
        ActionItemUi(
            key = MemoActionId.SHARE_TEXT.storageKey,
            icon = Icons.AutoMirrored.Outlined.TextSnippet,
            label = labels.shareText,
            onClick = { lifecycle.consume { commandHandler.shareText(selection) } },
            dismissAfterClick = false,
        ),
        if (commandHandler.hasLanShare) {
            ActionItemUi(
                key = MemoActionId.LAN_SHARE.storageKey,
                icon = Icons.Outlined.Wifi,
                label = labels.lanShare,
                onClick = { lifecycle.consume { commandHandler.lanShare(selection) } },
                dismissAfterClick = false,
            )
        } else {
            null
        },
        if (commandHandler.hasTogglePin) {
            ActionItemUi(
                key = MemoActionId.PIN.storageKey,
                icon = Icons.Outlined.PushPin,
                label = labels.pin,
                benchmarkTag = benchmarkMemoActionAnchor(MemoActionId.PIN),
                onClick = { lifecycle.consume { commandHandler.togglePin(selection) } },
                isHighlighted = isPinned,
                dismissAfterClick = false,
            )
        } else {
            null
        },
        if (commandHandler.hasJump) {
            ActionItemUi(
                key = MemoActionId.JUMP.storageKey,
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                label = labels.jump,
                benchmarkTag = benchmarkMemoActionAnchor(MemoActionId.JUMP),
                onClick = { lifecycle.consume { commandHandler.jump(selection) } },
                dismissAfterClick = false,
            )
        } else {
            null
        },
        if (commandHandler.hasVersionHistory) {
            ActionItemUi(
                key = MemoActionId.HISTORY.storageKey,
                icon = Icons.Outlined.History,
                label = labels.history,
                benchmarkTag = benchmarkMemoActionAnchor(MemoActionId.HISTORY),
                onClick = { lifecycle.consume { commandHandler.versionHistory(selection) } },
                dismissAfterClick = false,
            )
        } else {
            null
        },
        ActionItemUi(
            key = MemoActionId.EDIT.storageKey,
            icon = Icons.Outlined.Edit,
            label = labels.edit,
            benchmarkTag = benchmarkMemoActionAnchor(MemoActionId.EDIT),
            onClick = { lifecycle.hideAndConsume { commandHandler.edit(selection) } },
            dismissAfterClick = false,
        ),
        ActionItemUi(
            key = MemoActionId.DELETE.storageKey,
            icon = Icons.Outlined.Delete,
            label = labels.delete,
            benchmarkTag = benchmarkMemoActionAnchor(MemoActionId.DELETE),
            onClick = { lifecycle.hideAndConsume { commandHandler.delete(selection) } },
            isDestructive = true,
            dismissAfterClick = false,
            haptic = ActionItemHaptic.HEAVY,
        ),
    )

private fun benchmarkMemoActionAnchor(actionId: MemoActionId): String? =
    when (actionId) {
        MemoActionId.HISTORY -> BenchmarkAnchorContract.MEMO_ACTION_HISTORY
        MemoActionId.EDIT -> BenchmarkAnchorContract.MEMO_ACTION_EDIT
        MemoActionId.DELETE -> BenchmarkAnchorContract.MEMO_ACTION_DELETE
        else -> null
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
