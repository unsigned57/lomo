package com.lomo.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.util.LocalAppHapticFeedback

/**
 * Shared settings page app bar.
 *
 * M3 Expressive medium flexible bar: the title starts large, sits above the content and collapses
 * into the compact bar while the page scrolls. Every settings page renders its header through this
 * one bar so the feature keeps a single title scale and back affordance instead of a copy per page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsPageTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backButtonModifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    MediumFlexibleTopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            LomoIconButton(
                onClick = {
                    haptic.medium()
                    onBack()
                },
                modifier = backButtonModifier,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
        modifier = modifier,
    )
}
