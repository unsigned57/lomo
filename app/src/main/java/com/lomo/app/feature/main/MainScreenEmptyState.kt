package com.lomo.app.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * P2-008 Refactor: Extracted EmptyState from MainScreen.kt
 */
@Composable
internal fun MainEmptyState(
    searchQuery: String,
    selectedTag: String?,
    hasDirectory: Boolean,
    onSettings: () -> Unit,
) {
    val icon =
        when {
            !hasDirectory -> Icons.AutoMirrored.Rounded.NoteAdd
            searchQuery.isNotBlank() -> Icons.Rounded.Search
            selectedTag != null -> Icons.Rounded.Search
            else -> Icons.AutoMirrored.Rounded.NoteAdd
        }

    val title =
        when {
            !hasDirectory -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_directory_title)
            }

            searchQuery.isNotBlank() -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_matches_title)
            }

            selectedTag != null -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_tag_matches_title, selectedTag)
            }

            else -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_memos_title)
            }
        }

    val subtitle =
        when {
            !hasDirectory -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_directory_subtitle)
            }

            searchQuery.isNotBlank() -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_matches_subtitle)
            }

            selectedTag != null -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_tag_matches_subtitle)
            }

            else -> {
                androidx.compose.ui.res
                    .stringResource(com.lomo.app.R.string.empty_no_memos_subtitle)
            }
        }

    com.lomo.ui.component.common.EmptyState(
        icon = icon,
        title = title,
        description = subtitle,
        action =
            if (!hasDirectory) {
                {
                    Button(onClick = onSettings) {
                        Text(
                            androidx.compose.ui.res
                                .stringResource(com.lomo.app.R.string.action_go_to_settings),
                        )
                    }
                }
            } else {
                null
            },
    )
}
