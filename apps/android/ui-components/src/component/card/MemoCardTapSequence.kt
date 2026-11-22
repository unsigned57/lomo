package com.lomo.ui.component.card

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalViewConfiguration
import com.lomo.ui.util.AppHapticFeedback

/** The first release acts immediately; the second release edits instead of toggling again. */
internal class MemoCardTapSequence(private val doubleTapMinMillis: Long, private val doubleTapTimeoutMillis: Long) {
    private var firstReleaseMillis: Long? = null

    init {
        require(doubleTapMinMillis >= 0 && doubleTapTimeoutMillis >= doubleTapMinMillis)
    }

    fun release(nowMillis: Long): MemoCardTap {
        val firstRelease = firstReleaseMillis
        if (firstRelease != null) {
            require(nowMillis >= firstRelease) { "Memo tap timestamps must be monotonic" }
            if (nowMillis - firstRelease in doubleTapMinMillis..doubleTapTimeoutMillis) {
                reset()
                return MemoCardTap.Edit
            }
        }
        firstReleaseMillis = nowMillis
        return MemoCardTap.Click
    }

    fun reset() {
        firstReleaseMillis = null
    }
}

internal enum class MemoCardTap { Click, Edit }

@Composable
internal fun rememberMemoCardTapSequence(isDoubleTapEnabled: Boolean): MemoCardTapSequence {
    val viewConfiguration = LocalViewConfiguration.current
    return remember(viewConfiguration, isDoubleTapEnabled) {
        MemoCardTapSequence(viewConfiguration.doubleTapMinTimeMillis, viewConfiguration.doubleTapTimeoutMillis)
    }
}

/**
 * One card owns the tap sequence across summary/full-body replacement. Framework double-tap
 * confirmation must not delay the first expansion or let the second tap collapse it again.
 */
internal fun memoCardClickHandler(
    tapSequence: MemoCardTapSequence,
    haptic: AppHapticFeedback,
    isDoubleTapEnabled: Boolean,
    onDoubleClick: () -> Unit,
    onSingleClick: () -> Unit,
): () -> Unit = {
    haptic.medium()
    val tap = if (!isDoubleTapEnabled) MemoCardTap.Click else tapSequence.release(SystemClock.uptimeMillis())
    when (tap) {
        MemoCardTap.Click -> onSingleClick()
        MemoCardTap.Edit -> onDoubleClick()
    }
}

internal class MemoCardPointerHandlers(
    val effectiveOnClick: () -> Unit,
    val textLongClick: (() -> Unit)?,
    val onToggleExpanded: () -> Unit,
)

@Composable
internal fun rememberMemoCardPointerHandlers(
    tapSequence: MemoCardTapSequence,
    haptic: AppHapticFeedback,
    canExpand: Boolean,
    expandOnClick: Boolean,
    requestedExpanded: Boolean,
    updateExpanded: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    onMenuClick: (() -> Unit)?,
): MemoCardPointerHandlers {
    val currentCanExpand by rememberUpdatedState(canExpand)
    val currentExpandOnClick by rememberUpdatedState(expandOnClick)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    val currentOnMenuClick by rememberUpdatedState(onMenuClick)
    val currentRequestedExpanded by rememberUpdatedState(requestedExpanded)
    val currentUpdateExpanded by rememberUpdatedState(updateExpanded)
    val hasMenuClick = onMenuClick != null
    val isDoubleTapEnabled = onDoubleClick != null
    val textLongClick =
        remember(hasMenuClick, tapSequence, haptic) {
            if (!hasMenuClick) {
                null
            } else {
                {
                    tapSequence.reset()
                    haptic.longPress()
                    currentOnMenuClick?.invoke()
                    Unit
                }
            }
        }
    val effectiveOnClick =
        remember(tapSequence, haptic, isDoubleTapEnabled) {
            memoCardClickHandler(
                tapSequence = tapSequence,
                haptic = haptic,
                isDoubleTapEnabled = isDoubleTapEnabled,
                onDoubleClick = { currentOnDoubleClick?.invoke() },
                onSingleClick = {
                    if (currentExpandOnClick && currentCanExpand) {
                        currentUpdateExpanded(!currentRequestedExpanded)
                    }
                    currentOnClick()
                },
            )
        }
    val onToggleExpanded =
        remember(tapSequence) {
            {
                tapSequence.reset()
                currentUpdateExpanded(!currentRequestedExpanded)
            }
        }
    return remember(effectiveOnClick, textLongClick, onToggleExpanded) {
        MemoCardPointerHandlers(
            effectiveOnClick = effectiveOnClick,
            textLongClick = textLongClick,
            onToggleExpanded = onToggleExpanded,
        )
    }
}
