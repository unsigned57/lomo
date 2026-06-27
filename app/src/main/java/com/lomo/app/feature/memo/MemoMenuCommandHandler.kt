package com.lomo.app.feature.memo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.lomo.app.util.ShareUtils
import com.lomo.app.util.rememberShareUtils
import com.lomo.domain.model.Memo
import com.lomo.ui.theme.resolveCustomCanvasTypeface
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

data class MemoMenuPresentationState(
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
    val showJump: Boolean = false,
    val showVersionHistory: Boolean = false,
    val memoActionAutoReorderEnabled: Boolean = true,
    val memoActionOrder: ImmutableList<String> = persistentListOf(),
)

data class MemoMenuShareCardRequest(
    val content: String,
    val showTime: Boolean,
    val showSignature: Boolean,
    val signatureText: String,
    val timestamp: Long?,
    val tags: List<String>,
    val resolvedImagePaths: List<String>,
    val geoLocation: String?,
)

data class MemoMenuShareTextRequest(
    val content: String,
)

data class MemoMenuLanShareRequest(
    val content: String,
    val timestamp: Long,
)

data class MemoMenuTogglePinRequest(
    val memo: Memo,
    val pinned: Boolean,
)

class MemoMenuCommandHandler(
    val presentationState: MemoMenuPresentationState,
    private val onEditMemo: (Memo) -> Unit,
    private val onDeleteMemo: (Memo, String?) -> Unit,
    private val onShareCard: (MemoMenuShareCardRequest) -> Unit,
    private val onShareText: (MemoMenuShareTextRequest) -> Unit,
    private val onLanShare: ((MemoMenuLanShareRequest) -> Unit)? = null,
    private val onTogglePin: ((MemoMenuTogglePinRequest) -> Unit)? = null,
    private val onJump: ((MemoMenuSelection) -> Unit)? = null,
    private val onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
    private val onMemoActionInvoked: (MemoActionId) -> Unit,
    private val onMemoActionOrderChanged: (List<MemoActionId>) -> Unit,
) {
    val hasLanShare: Boolean
        get() = onLanShare != null

    val hasTogglePin: Boolean
        get() = onTogglePin != null

    val hasJump: Boolean
        get() = presentationState.showJump && onJump != null

    val hasVersionHistory: Boolean
        get() = presentationState.showVersionHistory && onVersionHistory != null

    fun edit(selection: MemoMenuSelection) {
        onEditMemo(selection.memo)
    }

    fun delete(selection: MemoMenuSelection) {
        onDeleteMemo(selection.memo, selection.anchoredAfterKey)
    }

    fun shareCard(selection: MemoMenuSelection) {
        val memo = selection.memo
        onShareCard(
            MemoMenuShareCardRequest(
                content = memo.content,
                showTime = presentationState.shareCardShowTime,
                showSignature = presentationState.shareCardShowSignature,
                signatureText = presentationState.shareCardSignatureText,
                timestamp = memo.timestamp,
                tags = memo.tags,
                resolvedImagePaths = selection.state.imageUrls,
                geoLocation = memo.geoLocation,
            ),
        )
    }

    fun shareText(selection: MemoMenuSelection) {
        onShareText(MemoMenuShareTextRequest(content = selection.memo.content))
    }

    fun lanShare(selection: MemoMenuSelection) {
        val handler = onLanShare ?: return
        val memo = selection.memo
        handler(
            MemoMenuLanShareRequest(
                content = memo.content,
                timestamp = memo.timestamp,
            ),
        )
    }

    fun togglePin(selection: MemoMenuSelection) {
        val handler = onTogglePin ?: return
        val memo = selection.memo
        handler(MemoMenuTogglePinRequest(memo = memo, pinned = !memo.isPinned))
    }

    fun jump(selection: MemoMenuSelection) {
        onJump?.invoke(selection)
    }

    fun versionHistory(selection: MemoMenuSelection) {
        onVersionHistory?.invoke(selection)
    }

    fun recordActionUsage(actionId: MemoActionId) {
        onMemoActionInvoked(actionId)
    }

    fun changeActionOrder(actionIds: List<MemoActionId>) {
        onMemoActionOrderChanged(actionIds)
    }
}

@Composable
fun rememberMemoMenuCommandHandler(
    presentationState: MemoMenuPresentationState,
    onEditMemo: (Memo) -> Unit,
    onDeleteMemo: (Memo, String?) -> Unit,
    onMemoActionInvoked: (MemoActionId) -> Unit,
    onLanShare: ((MemoMenuLanShareRequest) -> Unit)? = null,
    onTogglePin: ((Memo, Boolean) -> Unit)? = null,
    onJump: ((MemoMenuSelection) -> Unit)? = null,
    onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
    onMemoActionOrderChanged: (List<MemoActionId>) -> Unit,
): MemoMenuCommandHandler {
    val context = LocalContext.current
    val shareUtils = rememberShareUtils()
    val shareCardTypeface = remember(presentationState.customFontPath) {
        resolveCustomCanvasTypeface(presentationState.customFontPath)
    }
    val scope = rememberCoroutineScope()
    val editMemoState = rememberUpdatedState(onEditMemo)
    val deleteMemoState = rememberUpdatedState(onDeleteMemo)
    val lanShareState = rememberUpdatedState(onLanShare)
    val togglePinState = rememberUpdatedState(onTogglePin)
    val jumpState = rememberUpdatedState(onJump)
    val versionHistoryState = rememberUpdatedState(onVersionHistory)
    val actionInvokedState = rememberUpdatedState(onMemoActionInvoked)
    val actionOrderChangedState = rememberUpdatedState(onMemoActionOrderChanged)
    val hasLanShare = onLanShare != null
    val hasTogglePin = onTogglePin != null
    val hasJump = onJump != null
    val hasVersionHistory = onVersionHistory != null

    return remember(
        presentationState,
        context,
        shareUtils,
        scope,
        hasLanShare,
        hasTogglePin,
        hasJump,
        hasVersionHistory,
        shareCardTypeface,
    ) {
        MemoMenuCommandHandler(
            presentationState = presentationState,
            onEditMemo = { memo -> editMemoState.value(memo) },
            onDeleteMemo = { memo, anchoredAfterKey ->
                deleteMemoState.value(memo, anchoredAfterKey)
            },
            onShareCard = { request ->
                scope.launch {
                    shareMemoAsImage(
                        request = request,
                        context = context,
                        shareUtils = shareUtils,
                        bodyTypeface = shareCardTypeface,
                    )
                }
            },
            onShareText = { request ->
                shareUtils.shareMemoText(
                    context = context,
                    content = request.content,
                )
            },
            onLanShare =
                if (hasLanShare) {
                    { request -> lanShareState.value?.invoke(request) }
                } else {
                    null
                },
            onTogglePin =
                if (hasTogglePin) {
                    { request -> togglePinState.value?.invoke(request.memo, request.pinned) }
                } else {
                    null
                },
            onJump =
                if (hasJump) {
                    { state -> jumpState.value?.invoke(state) }
                } else {
                    null
                },
            onVersionHistory =
                if (hasVersionHistory) {
                    { selection -> versionHistoryState.value?.invoke(selection) }
                } else {
                    null
                },
            onMemoActionInvoked = { actionId -> actionInvokedState.value(actionId) },
            onMemoActionOrderChanged = { actionIds -> actionOrderChangedState.value(actionIds) },
        )
    }
}

private suspend fun shareMemoAsImage(
    request: MemoMenuShareCardRequest,
    context: Context,
    shareUtils: ShareUtils,
    bodyTypeface: android.graphics.Typeface?,
) {
    shareUtils.shareMemoAsImage(
        context = context,
        content = request.content,
        showTime = request.showTime,
        showSignature = request.showSignature,
        signatureText = request.signatureText,
        timestamp = request.timestamp,
        tags = request.tags,
        resolvedImagePaths = request.resolvedImagePaths,
        geoLocation = request.geoLocation,
        bodyTypeface = bodyTypeface,
    )
}
