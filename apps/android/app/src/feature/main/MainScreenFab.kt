package com.lomo.app.feature.main

import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.quickball.QuickBallActionSheet
import com.lomo.app.feature.quickball.quickBallIcons
import com.lomo.app.feature.quickball.quickBallLabels
import com.lomo.domain.model.QuickBallAction
import com.lomo.domain.model.QuickBallConfig
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.GestureBall
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun MainQuickBall(
    visible: Boolean,
    config: QuickBallConfig,
    actions: MainScreenActions,
    onScrollToTop: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    val execute: (QuickBallAction) -> Unit = { action ->
        when (action) {
            QuickBallAction.NEW_MEMO -> actions.onFabClick()
            QuickBallAction.RECORD -> actions.onRecord()
            QuickBallAction.LONG_TEXT -> actions.onLongText()
            QuickBallAction.SCROLL_TOP -> onScrollToTop()
            QuickBallAction.SEARCH -> actions.onSearch()
            QuickBallAction.FILTER -> actions.onOpenMemoFilterPanel()
            QuickBallAction.CLEAR_FILTER -> actions.onClearFilter()
            QuickBallAction.ACTION_MENU -> menuVisible = true
            QuickBallAction.DAILY_REVIEW -> actions.onDailyReviewClick()
            QuickBallAction.GALLERY -> actions.onGalleryClick()
            QuickBallAction.NONE -> Unit
        }
    }
    val motion = LocalLomoMotion.current.component
    val appearance = androidx.compose.animation.core.updateTransition(visible, label = "FabVisibility")
    val appearanceFraction by appearance.animateFloat(
        transitionSpec = { if (targetState) motion.defaultSpatialSpec() else motion.fastSpatialSpec() },
        label = "FabAppearance",
    ) { shown -> if (shown) 1f else 0f }
    if (appearance.currentState || appearance.targetState) {
        GestureBall(
            labels = quickBallLabels(config),
            icons = quickBallIcons(config),
            onGesture = { gesture -> if (visible) execute(config.action(gesture)) },
            modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_CREATE_FAB),
            appearanceFraction = appearanceFraction,
        )
    }
    if (menuVisible) {
        QuickBallActionSheet(
            title = stringResource(R.string.quick_ball_action_menu),
            actions = QuickBallAction.entries
                .filter { it != QuickBallAction.NONE && it != QuickBallAction.ACTION_MENU }
                .toImmutableList(),
            selectedAction = null,
            onDismiss = { menuVisible = false },
            onSelect = { action ->
                menuVisible = false
                execute(action)
            },
        )
    }
}
