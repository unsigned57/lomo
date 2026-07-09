package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.lomo.app.R

@Composable
internal fun memoSnapshotKeepCountSummary(count: Int): String =
    pluralStringResource(R.plurals.settings_memo_snapshot_keep_count_summary, count, count)

@Composable
internal fun memoSnapshotKeepAgeSummary(days: Int): String =
    pluralStringResource(R.plurals.settings_memo_snapshot_keep_age_summary, days, days)

@Composable
internal fun memoSnapshotCountOptionLabel(count: Int): String =
    pluralStringResource(R.plurals.settings_memo_snapshot_count_option, count, count)

@Composable
internal fun snapshotAgeOptionLabel(days: Int): String =
    pluralStringResource(R.plurals.settings_snapshot_age_option, days, days)
