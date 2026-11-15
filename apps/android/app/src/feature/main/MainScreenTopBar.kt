package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback

/**
 * Main screen app bar: the compact M3 Expressive bar keeps the Lomo brand mark on the same row as
 * the drawer, search and filter affordances, so the feed starts directly under one 64 dp row
 * instead of under a separate brand headline row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MainTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    menuToggle: MainSidebarToggle,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onClearFilter: () -> Unit,
    isFilterActive: Boolean,
) {
    val haptic = LocalAppHapticFeedback.current
    val componentMotion = LocalLomoMotion.current.component
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            MainTopBarIconButton(
                icon = menuToggle.icon(),
                contentDescription = stringResource(menuToggle.contentDescriptionRes()),
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_DRAWER_BUTTON),
                onClick = {
                    haptic.medium()
                    onMenu()
                },
            )
        },
        actions = {
            AnimatedVisibility(
                visible = isFilterActive,
                enter =
                fadeIn(componentMotion.fastEffectsSpec()) +
                    scaleIn(initialScale = 0.85f, animationSpec = componentMotion.fastSpatialSpec()) +
                    expandHorizontally(animationSpec = componentMotion.fastSpatialSpec()),
                exit =
                fadeOut(componentMotion.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = componentMotion.fastSpatialSpec()) +
                    shrinkHorizontally(animationSpec = componentMotion.fastSpatialSpec()),
            ) {
                MainTopBarIconButton(
                    icon = LomoIcons.Close,
                    contentDescription = stringResource(R.string.cd_clear_filter),
                    onClick = {
                        haptic.medium()
                        onClearFilter()
                    },
                )
            }
            MainTopBarIconButton(
                icon = LomoIcons.Search,
                contentDescription = stringResource(R.string.cd_search),
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_SEARCH_BUTTON),
                onClick = {
                    haptic.medium()
                    onSearch()
                },
            )
            MainTopBarIconButton(
                icon = LomoIcons.FilterList,
                contentDescription = stringResource(R.string.cd_filter),
                isHighlighted = isFilterActive,
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_FILTER_BUTTON),
                onClick = {
                    haptic.medium()
                    onFilter()
                },
            )
        },
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainTopBarIconButton(
    icon: LomoIconSpec,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    onClick: () -> Unit,
) {
    LomoIconButton(
        onClick = onClick,
        colors =
        if (isHighlighted) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
        modifier = modifier,
    ) {
        LomoIcon(
            icon = icon,
            contentDescription = contentDescription,
        )
    }
}

/** The navigation button names what a press does: open the drawer, or collapse / expand the docked pane. */
private fun MainSidebarToggle.contentDescriptionRes(): Int = when (this) {
    MainSidebarToggle.OpenDrawer -> R.string.cd_menu
    MainSidebarToggle.CollapsePane -> R.string.cd_collapse_sidebar
    MainSidebarToggle.ExpandPane -> R.string.cd_expand_sidebar
}

private fun MainSidebarToggle.icon(): LomoIconSpec = when (this) {
    MainSidebarToggle.CollapsePane -> LomoIcons.MenuOpen
    MainSidebarToggle.OpenDrawer, MainSidebarToggle.ExpandPane -> LomoIcons.Menu
}
