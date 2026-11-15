package com.lomo.ui.component.common

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState

/**
 * App-scoped [HazeState] shared across the main content window (`zIndex = 0f`) and modal sheet
 * windows (`zIndex = 1f`) so overlays like the share radial menu blur the entire backdrop.
 */
val LocalLomoHazeState = staticCompositionLocalOf<HazeState?> { null }
