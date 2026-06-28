package com.lomo.ui.component.dialog

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.ui.theme.AppShapes

internal object SyncConflictDialogTokens {
    val SheetShape = AppShapes.ExtraLargeTop

    private const val ScrimAlpha = 0.4f

    fun scrimColor(colorScheme: ColorScheme): Color =
        colorScheme.scrim.copy(alpha = ScrimAlpha)
}
