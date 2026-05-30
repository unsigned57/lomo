package com.lomo.app.feature.memo

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class MemoBackfillSelectionState {
    var timestampMillis: Long? by mutableStateOf(null)
        private set

    fun setTimestampForCreate(
        timestampMillis: Long,
        isEditingExistingMemo: Boolean,
    ) {
        if (!isEditingExistingMemo) {
            this.timestampMillis = timestampMillis
        }
    }

    fun timestampMillisForCreateSubmit(isEditingExistingMemo: Boolean): Long? =
        if (isEditingExistingMemo) {
            null
        } else {
            timestampMillis
        }

    fun clear() {
        timestampMillis = null
    }
}
