package com.lomo.ui.component.common

import android.os.Build
import android.os.Trace
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize

/** A visible sheet enters after its actual content has laid out, including on the first open. */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class ModalSheetPresentation internal constructor(val sheetState: SheetState, private val entry: ModalSheetEntry) {
    fun onContentMeasured(size: IntSize) {
        entry.onContentMeasured(size.height)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberModalSheetPresentation(): ModalSheetPresentation {
    val entry = remember { ModalSheetEntry() }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { value -> value == SheetValue.Hidden || entry.canExpand },
    )
    LaunchedEffect(sheetState, entry) {
        entry.awaitContentFrame()
        val cookie = System.identityHashCode(sheetState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("lomoModalSheetEnter", cookie)
        }
        try {
            sheetState.show()
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection("lomoModalSheetEnter", cookie)
            }
        }
    }
    return remember(sheetState, entry) { ModalSheetPresentation(sheetState, entry) }
}
