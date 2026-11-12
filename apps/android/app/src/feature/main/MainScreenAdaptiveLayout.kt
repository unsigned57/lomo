package com.lomo.app.feature.main

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlin.math.roundToInt

/**
 * Where the main sidebar lives. A modal drawer floats over the feed; a docked pane shares the window
 * width with it, so opening the sidebar resizes the feed instead of pushing it — and its search and
 * filter actions — off-screen.
 */
internal enum class MainSidebarPlacement {
    ModalDrawer,
    DockedPane,
}

internal val MainSidebarPaneWidth = 300.dp

/** Material's navigation-drawer maximum, and the scrim strip a modal drawer must leave tappable. */
private val MainModalDrawerMaxWidth = 360.dp
private val MainModalDrawerScrimStrip = 56.dp

/**
 * A docked pane needs an expanded-width window that is also tall enough to read a feed: a phone held
 * sideways is wide but short, and keeps the modal drawer it has in portrait.
 */
internal fun resolveMainSidebarPlacement(windowSizeClass: WindowSizeClass): MainSidebarPlacement = if (
    windowSizeClass.isAtLeastBreakpoint(
        widthDpBreakpoint = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        heightDpBreakpoint = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )
) {
    MainSidebarPlacement.DockedPane
} else {
    MainSidebarPlacement.ModalDrawer
}

internal fun resolveMainModalDrawerWidth(windowWidth: Dp): Dp =
    minOf(windowWidth - MainModalDrawerScrimStrip, MainModalDrawerMaxWidth).coerceAtLeast(0.dp)

/** Pane and feed positions for one frame of the pane's slide. */
internal data class MainDockedGeometry(val paneOffsetPx: Int, val feedOffsetPx: Int, val feedWidthPx: Int)

/** The pane slides in from the start edge and the feed gives up exactly the width the pane covers. */
internal fun resolveMainDockedGeometry(
    windowWidthPx: Int,
    paneWidthPx: Int,
    paneVisibility: Float,
): MainDockedGeometry {
    val coveredPx = (paneWidthPx * paneVisibility.coerceIn(0f, 1f)).roundToInt()
    return MainDockedGeometry(
        paneOffsetPx = coveredPx - paneWidthPx,
        feedOffsetPx = coveredPx,
        feedWidthPx = windowWidthPx - coveredPx,
    )
}

/** What a press on the top bar's navigation button does, which also names the button for accessibility. */
internal enum class MainSidebarToggle {
    OpenDrawer,
    CollapsePane,
    ExpandPane,
}

internal fun resolveMainSidebarToggle(placement: MainSidebarPlacement, dockedPaneShown: Boolean): MainSidebarToggle =
    when (placement) {
        MainSidebarPlacement.ModalDrawer -> MainSidebarToggle.OpenDrawer

        MainSidebarPlacement.DockedPane ->
            if (dockedPaneShown) MainSidebarToggle.CollapsePane else MainSidebarToggle.ExpandPane
    }

/**
 * Open state of the sidebar in its current placement. The modal drawer and the docked pane keep
 * separate memories, so rotating a tablet or unfolding a phone restores what each form last showed.
 */
@Stable
internal class MainSidebarState(
    val placement: MainSidebarPlacement,
    val drawerState: DrawerState,
    private val dockedPaneShown: MutableState<Boolean>,
) {
    val isDockedPaneShown: Boolean
        get() = dockedPaneShown.value

    val toggleKind: MainSidebarToggle
        get() = resolveMainSidebarToggle(placement, dockedPaneShown.value)

    /** Only a modal drawer sits above the feed and hides its floating actions. */
    val coversFeed: Boolean
        get() = placement == MainSidebarPlacement.ModalDrawer && drawerState.isOpen

    suspend fun toggle() {
        when (placement) {
            MainSidebarPlacement.DockedPane -> dockedPaneShown.value = !dockedPaneShown.value

            MainSidebarPlacement.ModalDrawer ->
                if (drawerState.targetValue == DrawerValue.Open) drawerState.close() else drawerState.open()
        }
    }

    /** Leaving the feed closes a modal drawer; a docked pane stays where the user put it. */
    suspend fun closeOverlay() {
        if (placement == MainSidebarPlacement.ModalDrawer) drawerState.close()
    }
}
