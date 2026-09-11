package com.lomo.app.feature.search

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.domain.model.MemoSearchMode
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun SearchBarTrailingIcons(
    query: String,
    searchMode: MemoSearchMode,
    isFilterActive: Boolean,
    haptic: AppHapticFeedback,
    onClearQuery: () -> Unit,
    onToggleSearchMode: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (query.isNotEmpty()) {
            IconButton(
                onClick = {
                    haptic.medium()
                    onClearQuery()
                },
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.SEARCH_CLEAR),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_clear_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val modeDescription =
            stringResource(
                if (searchMode == MemoSearchMode.Fuzzy) {
                    R.string.search_mode_switch_to_fulltext
                } else {
                    R.string.search_mode_switch_to_fuzzy
                },
            )
        TextButton(
            onClick = {
                haptic.medium()
                onToggleSearchMode()
            },
            modifier = Modifier.semantics { contentDescription = modeDescription },
        ) {
            Text(
                text = stringResource(R.string.search_mode_fuzzy),
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (searchMode == MemoSearchMode.Fuzzy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        IconButton(
            onClick = {
                haptic.medium()
                onOpenFilter()
            },
        ) {
            val descriptionRes =
                if (isFilterActive) R.string.search_filter_active_cd else R.string.search_filter_open
            val iconTint =
                if (isFilterActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            Icon(
                Icons.Rounded.FilterAlt,
                contentDescription = stringResource(descriptionRes),
                tint = iconTint,
            )
        }
    }
}
