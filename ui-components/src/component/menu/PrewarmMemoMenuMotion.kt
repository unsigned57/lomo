package com.lomo.ui.component.menu

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val PREWARM_IDLE_FRAMES = 3
private const val PREWARM_COMPOSE_FRAMES = 4

private enum class MemoMenuPrewarmPhase {
    PENDING,
    COMPOSING,
    DONE,
}

/**
 * Pre-allocates the Material3 `ModalBottomSheet` Popup window and `AnchoredDraggableState`
 * during idle frames after the host screen settles, so the user's first menu open does not
 * pay the binder-IPC and state-initialization cost on the critical animation frame.
 *
 * The warmup briefly composes a real `ModalBottomSheet` made fully invisible via alpha/scrim
 * transparency, then unmounts it. The next time `MemoMenuHost` composes its own
 * `ModalBottomSheet`, the Popup class graph and WindowManager view path are already warm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrewarmMemoMenuMotion(modifier: Modifier = Modifier) {
    var phase by remember { mutableStateOf(MemoMenuPrewarmPhase.PENDING) }

    LaunchedEffect(Unit) {
        repeat(PREWARM_IDLE_FRAMES) { withFrameNanos { } }
        phase = MemoMenuPrewarmPhase.COMPOSING
        repeat(PREWARM_COMPOSE_FRAMES) { withFrameNanos { } }
        phase = MemoMenuPrewarmPhase.DONE
    }

    if (phase == MemoMenuPrewarmPhase.COMPOSING) {
        val warmupSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = warmupSheetState,
            onDismissRequest = {},
            modifier = modifier.alpha(0f),
            scrimColor = Color.Transparent,
            containerColor = Color.Transparent,
            dragHandle = null,
            tonalElevation = 0.dp,
        ) {
            Spacer(Modifier.height(1.dp))
        }
    }
}
