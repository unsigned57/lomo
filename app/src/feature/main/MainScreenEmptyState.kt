package com.lomo.app.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.lomo.ui.util.LocalAppHapticFeedback

/**
 * P2-008 Refactor: Extracted EmptyState from MainScreen.kt
 */
@Composable
internal fun MainEmptyState(
    searchQuery: String,
    hasDirectory: Boolean,
    onSettings: () -> Unit,
) {
    val content = resolveMainEmptyStateContent(searchQuery, hasDirectory)

    com.lomo.ui.component.common.EmptyState(
        icon = content.icon,
        title = content.title,
        description = content.subtitle,
        action =
            if (!hasDirectory) {
                { MainEmptyStateSettingsAction(onSettings = onSettings) }
            } else {
                null
            },
    )
}

@Composable
private fun MainEmptyStateSettingsAction(onSettings: () -> Unit) {
    val haptic = LocalAppHapticFeedback.current
    Button(
        onClick = {
            haptic.medium()
            onSettings()
        },
    ) {
        Text(
            androidx.compose.ui.res
                .stringResource(com.lomo.app.R.string.action_go_to_settings),
        )
    }
}

@Composable
private fun resolveMainEmptyStateContent(
    searchQuery: String,
    hasDirectory: Boolean,
): MainEmptyStateContent =
    when {
        !hasDirectory -> {
            MainEmptyStateContent(
                icon = Icons.AutoMirrored.Rounded.NoteAdd,
                title =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.empty_no_directory_title),
                subtitle =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.empty_no_directory_subtitle),
            )
        }

        searchQuery.isNotBlank() -> {
            MainEmptyStateContent(
                icon = Icons.Rounded.Search,
                title =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.empty_no_matches_title),
                subtitle =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.empty_no_matches_subtitle),
            )
        }

        else -> {
            MainEmptyStateContent(
                icon = Icons.AutoMirrored.Rounded.NoteAdd,
                title =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.empty_no_memos_title),
                subtitle =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.app.R.string.empty_no_memos_subtitle),
            )
        }
    }

private data class MainEmptyStateContent(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)
