package com.lomo.app.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchor

/**
 * Extracted TopBar from MainScreen.kt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onClearFilter: () -> Unit,
    isFilterActive: Boolean,
    showNavigationIcon: Boolean = true,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (showNavigationIcon) {
                MainTopBarIconButton(
                    icon = Icons.Rounded.Menu,
                    contentDescription = androidx.compose.ui.res.stringResource(com.lomo.app.R.string.cd_menu),
                    modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_DRAWER_BUTTON),
                    onClick = {
                        haptic.medium()
                        onMenu()
                    },
                )
            }
        },
        actions = {
            if (isFilterActive) {
                MainTopBarIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(com.lomo.app.R.string.cd_clear_filter),
                    onClick = {
                        haptic.medium()
                        onClearFilter()
                    },
                )
            }
            MainTopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = androidx.compose.ui.res.stringResource(com.lomo.app.R.string.cd_search),
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.MAIN_SEARCH_BUTTON),
                onClick = {
                    haptic.medium()
                    onSearch()
                },
            )
            MainTopBarIconButton(
                icon = Icons.Rounded.FilterList,
                contentDescription = androidx.compose.ui.res.stringResource(com.lomo.app.R.string.cd_filter),
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

@Composable
private fun MainTopBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
    }
}
