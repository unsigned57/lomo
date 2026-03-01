package com.lomo.app.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

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
                IconButton(
                    onClick = {
                        haptic.medium()
                        onMenu()
                    },
                ) {
                    Icon(
                        Icons.Rounded.Menu,
                        androidx.compose.ui.res
                            .stringResource(com.lomo.app.R.string.cd_menu),
                    )
                }
            }
        },
        actions = {
            if (isFilterActive) {
                IconButton(
                    onClick = {
                        haptic.medium()
                        onClearFilter()
                    },
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        androidx.compose.ui.res
                            .stringResource(com.lomo.app.R.string.cd_clear_filter),
                    )
                }
            }
            IconButton(
                onClick = {
                    haptic.medium()
                    onSearch()
                },
            ) {
                Icon(
                    Icons.Rounded.Search,
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.cd_search),
                )
            }
            IconButton(
                onClick = {
                    haptic.medium()
                    onFilter()
                },
            ) {
                Icon(
                    Icons.Rounded.FilterList,
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.cd_filter),
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        scrollBehavior = scrollBehavior,
    )
}
