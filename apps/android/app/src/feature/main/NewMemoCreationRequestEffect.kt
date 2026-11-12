package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.lomo.app.feature.common.PendingUiEvent
import kotlinx.coroutines.flow.Flow

/** Consumes the existing acknowledged queue within the screen's presentation lifetime. */
@Composable
internal fun NewMemoCreationRequestEffect(
    events: Flow<List<PendingUiEvent<PendingNewMemoCreationRequest>>>,
    onRequest: (PendingUiEvent<PendingNewMemoCreationRequest>) -> Unit,
) {
    val latestOnRequest by rememberUpdatedState(onRequest)
    LaunchedEffect(events) {
        events.collect { requests ->
            requests.lastOrNull()?.let(latestOnRequest)
        }
    }
}
