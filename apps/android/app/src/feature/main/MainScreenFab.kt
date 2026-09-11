package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.app.R
import com.lomo.ui.theme.MotionTokens

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MainFab(
    isVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    Surface(
        shape = FloatingActionButtonDefaults.extendedFabShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
        modifier = modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_CREATE_FAB),
    ) {
        Row(
            modifier =
                Modifier
                    .clip(FloatingActionButtonDefaults.extendedFabShape)
                    .combinedClickable(
                        onClick = {
                            haptic.heavy()
                            onClick()
                        },
                        onLongClick = onLongClick,
                    ).padding(start = 16.dp, end = if (isVisible) 20.dp else 16.dp)
                    .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription =
                    androidx.compose.ui.res
                        .stringResource(R.string.fab_new_memo),
            )
            MainFabAnimatedLabel(isVisible = isVisible)
        }
    }
}

@Composable
private fun MainFabAnimatedLabel(isVisible: Boolean) {
    AnimatedVisibility(
        visible = isVisible,
        enter =
            expandHorizontally(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasized,
                    ),
            ) +
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationMedium2,
                        ),
                ),
        exit =
            shrinkHorizontally(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasized,
                    ),
            ) +
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationMedium2,
                        ),
                ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(12.dp))
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(R.string.fab_new_memo),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
