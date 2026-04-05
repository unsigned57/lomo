package com.lomo.app.feature.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class NewMemoInsertAnimationState(
    val awaitingInsertedTopMemo: Boolean = false,
    val previousTopMemoId: String? = null,
    val blankSpaceMemoId: String? = null,
    val gapReadyMemoId: String? = null,
    val pendingRevealMemoId: String? = null,
) {
    val blocksPlacementSpring: Boolean
        get() =
            awaitingInsertedTopMemo ||
                blankSpaceMemoId != null ||
                gapReadyMemoId != null ||
                pendingRevealMemoId != null
}

internal class NewMemoInsertAnimationSession(
    initialState: NewMemoInsertAnimationState = NewMemoInsertAnimationState(),
) {
    var state by mutableStateOf(initialState)
        private set

    fun arm(previousTopMemoId: String?): Boolean {
        if (
            state.awaitingInsertedTopMemo ||
            state.blankSpaceMemoId != null ||
            state.gapReadyMemoId != null ||
            state.pendingRevealMemoId != null
        ) {
            return false
        }

        state =
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = previousTopMemoId,
            )
        return true
    }

    fun markInsertedTopMemoReady(insertedTopMemoId: String?): String? {
        val currentState = state
        if (!currentState.awaitingInsertedTopMemo) {
            return null
        }
        if (insertedTopMemoId == null || insertedTopMemoId == currentState.previousTopMemoId) {
            return null
        }

        state =
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                blankSpaceMemoId = insertedTopMemoId,
            )
        return insertedTopMemoId
    }

    fun markBlankSpacePrepared(memoId: String) {
        if (state.blankSpaceMemoId == memoId) {
            state =
                NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    gapReadyMemoId = memoId,
                )
        }
    }

    fun markRevealReady(memoId: String) {
        if (state.gapReadyMemoId == memoId) {
            state =
                NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    pendingRevealMemoId = memoId,
                )
        }
    }

    fun clearReveal(memoId: String) {
        if (state.pendingRevealMemoId == memoId) {
            state = NewMemoInsertAnimationState()
        }
    }
}
