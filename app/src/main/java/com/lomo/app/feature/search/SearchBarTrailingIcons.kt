package com.lomo.app.feature.search

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.util.AppHapticFeedback

@Composable
internal fun SearchBarTrailingIcons(
    query: String,
    isFilterActive: Boolean,
    haptic: AppHapticFeedback,
    onClearQuery: () -> Unit,
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
