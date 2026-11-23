package com.lomo.app.feature.memo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.util.ShareUtils
import com.lomo.app.util.rememberShareUtils
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

data class MemoMenuPresentationState(
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
)

data class MemoMenuShareCardRequest(
    val content: String,
    val showTime: Boolean,
    val showSignature: Boolean,
    val signatureText: String,
    val createdAtText: String = "",
    val timestamp: Long? = null,
    val tags: List<String> = emptyList(),
    val resolvedImagePaths: List<String> = emptyList(),
)

data class MemoMenuShareTextRequest(val content: String)

data class MemoMenuLanShareRequest(val content: String, val timestamp: Long)

/**
 * The screen-specific action occupying the third menu tile. Exactly one exists per screen:
 * the main list pins in place, every other memo surface jumps back to the main list.
 */
sealed interface MemoMenuContextAction {
    data class TogglePin(val setPinned: (Memo, Boolean) -> Unit) : MemoMenuContextAction

    data class Jump(val jump: (MemoMenuSelection) -> Unit) : MemoMenuContextAction
}

class MemoMenuCommandHandler(
    val presentationState: MemoMenuPresentationState,
    private val onEditMemo: (String) -> Unit,
    private val onDeleteMemo: (Memo, String?) -> Unit,
    private val onShareCard: (MemoMenuShareCardRequest) -> Unit,
    private val onShareText: (MemoMenuShareTextRequest) -> Unit,
    private val onCopyMemo: (MemoMenuSelection, String) -> Unit,
    val contextAction: MemoMenuContextAction,
    private val onLanShare: ((MemoMenuLanShareRequest) -> Unit)? = null,
    private val onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
) {
    val hasLanShare: Boolean
        get() = onLanShare != null

    val hasVersionHistory: Boolean
        get() = onVersionHistory != null

    fun edit(selection: MemoMenuSelection) {
        onEditMemo(selection.memo.id)
    }

    fun delete(selection: MemoMenuSelection) {
        onDeleteMemo(selection.memo, selection.anchoredAfterKey)
    }

    fun copy(selection: MemoMenuSelection, label: String) {
        onCopyMemo(selection, label)
    }

    fun shareCard(selection: MemoMenuSelection) {
        val memo = selection.memo
        onShareCard(
            MemoMenuShareCardRequest(
                content = memo.content,
                showTime = presentationState.shareCardShowTime,
                showSignature = presentationState.shareCardShowSignature,
                signatureText = presentationState.shareCardSignatureText,
                createdAtText = memo.displayTimestamp(),
                timestamp = memo.timestamp,
                tags = memo.tags,
                resolvedImagePaths = selection.state.imageUrls,
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

    fun runContextAction(selection: MemoMenuSelection) {
        when (contextAction) {
            is MemoMenuContextAction.TogglePin -> contextAction.setPinned(selection.memo, !selection.memo.isPinned)
            is MemoMenuContextAction.Jump -> contextAction.jump(selection)
        }
    }

    fun versionHistory(selection: MemoMenuSelection) {
        onVersionHistory?.invoke(selection)
    }
}

@Composable
fun rememberMemoMenuCommandHandler(
    presentationState: MemoMenuPresentationState,
    onEditMemo: (String) -> Unit,
    onDeleteMemo: (Memo, String?) -> Unit,
    contextAction: MemoMenuContextAction,
    onLanShare: ((MemoMenuLanShareRequest) -> Unit)? = null,
    onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
): MemoMenuCommandHandler {
    val context = LocalContext.current
    val shareUtils = rememberShareUtils()
    val memoQueryRepository = koinInject<MemoQueryRepository>()
    val dispatcherProvider = koinInject<DispatcherProvider>()
    val copyFailedMessage = stringResource(R.string.memo_copy_failed)
    val scope = rememberCoroutineScope()
    val editMemoState = rememberUpdatedState(onEditMemo)
    val deleteMemoState = rememberUpdatedState(onDeleteMemo)
    val lanShareState = rememberUpdatedState(onLanShare)
    val versionHistoryState = rememberUpdatedState(onVersionHistory)
    val hasLanShare = onLanShare != null
    val hasVersionHistory = onVersionHistory != null

    return remember(
        presentationState,
        context,
        shareUtils,
        memoQueryRepository,
        dispatcherProvider,
        copyFailedMessage,
        scope,
        contextAction,
        hasLanShare,
        hasVersionHistory,
    ) {
        MemoMenuCommandHandler(
            presentationState = presentationState,
            onEditMemo = { memoId -> editMemoState.value(memoId) },
            onDeleteMemo = { memo, anchoredAfterKey ->
                deleteMemoState.value(memo, anchoredAfterKey)
            },
            onShareCard = { request ->
                scope.launch {
                    shareMemoAsImage(
                        request = request,
                        context = context,
                        shareUtils = shareUtils,
                    )
                }
            },
            onShareText = { request ->
                shareUtils.shareMemoText(
                    context = context,
                    content = request.content,
                )
            },
            contextAction = contextAction,
            onCopyMemo = { selection, label ->
                scope.launch {
                    copyMemoToClipboard(
                        context,
                        memoQueryRepository,
                        selection,
                        label,
                        copyFailedMessage,
                        dispatcherProvider,
                    )
                }
            },
            onLanShare =
            if (hasLanShare) {
                { request -> lanShareState.value?.invoke(request) }
            } else {
                null
            },
            onVersionHistory =
            if (hasVersionHistory) {
                { selection -> versionHistoryState.value?.invoke(selection) }
            } else {
                null
            },
        )
    }
}

private suspend fun shareMemoAsImage(request: MemoMenuShareCardRequest, context: Context, shareUtils: ShareUtils) {
    shareUtils.shareMemoAsImage(
        context = context,
        content = request.content,
        showTime = request.showTime,
        showSignature = request.showSignature,
        signatureText = request.signatureText,
        createdAtText = request.createdAtText,
        timestamp = request.timestamp,
        tags = request.tags,
        resolvedImagePaths = request.resolvedImagePaths,
    )
}
