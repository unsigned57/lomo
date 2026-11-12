package com.lomo.app.feature.memo

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import kotlinx.coroutines.launch

/**
 * Returns the [MemoEditorSheetHost] committed-autosave callback: it posts the localized
 * "saved" confirmation on [snackbarHostState]. The host only invokes it after a durable
 * commit, so rejected or failed submissions never surface this snackbar.
 */
@Composable
internal fun rememberAutoSaveCommittedNotifier(snackbarHostState: SnackbarHostState): () -> Unit {
    val message = stringResource(R.string.memo_auto_saved)
    val scope = rememberCoroutineScope()
    return remember(snackbarHostState, message, scope) {
        { scope.launch { snackbarHostState.showSnackbar(message) } }
    }
}
