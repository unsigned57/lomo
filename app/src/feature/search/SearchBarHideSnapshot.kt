package com.lomo.app.feature.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

internal data class SearchBarHideSnapshot(
    val canScrollContent: Boolean,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffsetPx: Int,
)

internal fun shouldAllowSearchBarScroll(snapshot: SearchBarHideSnapshot): Boolean = snapshot.canScrollContent

internal fun resolveSyncedSearchBarOffsetPx(
    currentOffsetPx: Float,
    consumedContentDeltaYPx: Float,
    maxOffsetPx: Float,
): Float {
    if (maxOffsetPx <= 0f) {
        return 0f
    }
    return (currentOffsetPx - consumedContentDeltaYPx).coerceIn(0f, maxOffsetPx)
}

@Composable
internal fun rememberSearchBarSynchronizedScrollConnection(
    listState: LazyListState,
    resultListActive: Boolean,
    currentOffsetPx: () -> Float,
    maxOffsetPx: () -> Float,
    onOffsetPxChange: (Float) -> Unit,
): NestedScrollConnection {
    val allowSearchBarScrollState =
        rememberSearchBarScrollState(
            listState = listState,
            resultListActive = resultListActive,
        )
    val currentOffsetPxState = rememberUpdatedState(currentOffsetPx)
    val maxOffsetPxState = rememberUpdatedState(maxOffsetPx)
    val onOffsetPxChangeState = rememberUpdatedState(onOffsetPxChange)
    return remember(allowSearchBarScrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (allowSearchBarScrollState.value || currentOffsetPxState.value() > 0f) {
                    val nextOffset =
                        resolveSyncedSearchBarOffsetPx(
                            currentOffsetPx = currentOffsetPxState.value(),
                            consumedContentDeltaYPx = consumed.y,
                            maxOffsetPx = maxOffsetPxState.value(),
                        )
                    onOffsetPxChangeState.value(nextOffset)
                }
                return Offset.Zero
            }
        }
    }
}

@Composable
private fun rememberSearchBarScrollState(
    listState: LazyListState,
    resultListActive: Boolean,
): State<Boolean> =
    remember(listState, resultListActive) {
        derivedStateOf {
            shouldAllowSearchBarScroll(
                snapshot =
                    SearchBarHideSnapshot(
                        canScrollContent = resultListActive &&
                            (listState.canScrollBackward || listState.canScrollForward),
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                    ),
            )
        }
    }
