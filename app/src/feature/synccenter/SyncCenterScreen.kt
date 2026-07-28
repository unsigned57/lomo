package com.lomo.app.feature.synccenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.R
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.ui.theme.AppSpacing

/**
 * Stage-5 dark Sync Center Compose shell (P5-10).
 *
 * Not registered in production navigation. Host / dark prototype only until P5-13.
 * Phone: single-column pane routes. Expanded width: list-detail dual pane for conflicts.
 */

internal val SyncCenterMinTouchTarget = 48.dp

@Composable
fun SyncCenterRoute(
    viewModel: SyncCenterViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isListDetail =
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    LaunchedEffect(isListDetail) {
        viewModel.dispatch(SyncCenterIntent.SetListDetail(isListDetail))
    }

    SyncCenterScreen(
        state = uiState,
        onIntent = viewModel::dispatch,
        onClose = onClose,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCenterScreen(
    state: SyncCenterUiState,
    onIntent: (SyncCenterIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag("sync_center_root"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_center_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier =
                            Modifier
                                .defaultMinSize(
                                    minWidth = SyncCenterMinTouchTarget,
                                    minHeight = SyncCenterMinTouchTarget,
                                )
                                .semantics {
                                    contentDescription =
                                        // stringResource not available in semantics lambda safely —
                                        // use literal role description via testTag on button
                                        "close"
                                    role = Role.Button
                                }
                                .testTag("sync_center_close"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sync_center_close),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onIntent(SyncCenterIntent.Refresh) },
                        modifier =
                            Modifier
                                .defaultMinSize(
                                    minWidth = SyncCenterMinTouchTarget,
                                    minHeight = SyncCenterMinTouchTarget,
                                )
                                .testTag("sync_center_refresh"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.sync_center_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            SyncCenterNavTabs(
                pane = state.pane,
                isListDetail = state.layout.isListDetail,
                onIntent = onIntent,
            )
            HorizontalDivider()
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(AppSpacing.Medium),
            ) {
                when (val load = state.load) {
                    SyncCenterLoadState.Idle ->
                        SyncCenterEmptyPrompt()

                    SyncCenterLoadState.Loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.testTag("sync_center_loading"))
                        }

                    is SyncCenterLoadState.Failed ->
                        SyncCenterErrorPane(
                            message = load.message,
                            onRetry = { onIntent(SyncCenterIntent.Refresh) },
                        )

                    is SyncCenterLoadState.Ready ->
                        SyncCenterReadyBody(
                            state = state,
                            ready = load,
                            onIntent = onIntent,
                        )
                }
            }
        }
    }
}

@Composable
private fun SyncCenterNavTabs(
    pane: SyncCenterPane,
    isListDetail: Boolean,
    onIntent: (SyncCenterIntent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SyncCenterTabChip(
            selected = pane == SyncCenterPane.Overview,
            label = stringResource(R.string.sync_center_tab_overview),
            onClick = { onIntent(SyncCenterIntent.NavigateOverview) },
            testTag = "sync_center_tab_overview",
        )
        SyncCenterTabChip(
            selected =
                pane == SyncCenterPane.Conflicts ||
                    pane == SyncCenterPane.ConflictDetail ||
                    (isListDetail && pane == SyncCenterPane.Conflicts),
            label = stringResource(R.string.sync_center_tab_conflicts),
            onClick = { onIntent(SyncCenterIntent.NavigateConflicts) },
            testTag = "sync_center_tab_conflicts",
        )
        SyncCenterTabChip(
            selected = pane == SyncCenterPane.Recovery,
            label = stringResource(R.string.sync_center_tab_recovery),
            onClick = { onIntent(SyncCenterIntent.NavigateRecovery) },
            testTag = "sync_center_tab_recovery",
        )
    }
}

@Composable
private fun SyncCenterTabChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    testTag: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier =
            Modifier
                .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                .testTag(testTag)
                .semantics { role = Role.Tab },
    )
}

@Composable
private fun SyncCenterReadyBody(
    state: SyncCenterUiState,
    ready: SyncCenterLoadState.Ready,
    onIntent: (SyncCenterIntent) -> Unit,
) {
    when (state.pane) {
        SyncCenterPane.Overview ->
            SyncCenterOverviewPane(
                config = ready.config,
                session = ready.session,
                onOpenConflicts = { onIntent(SyncCenterIntent.NavigateConflicts) },
                onCancel = { onIntent(SyncCenterIntent.CancelSession) },
            )

        SyncCenterPane.Conflicts ->
            if (state.layout.isListDetail) {
                SyncCenterListDetailPane(
                    ready = ready,
                    onIntent = onIntent,
                )
            } else {
                SyncCenterConflictListPane(
                    ready = ready,
                    onIntent = onIntent,
                )
            }

        SyncCenterPane.ConflictDetail ->
            SyncCenterConflictDetailPane(
                ready = ready,
                path = selectedConflict(state),
                onIntent = onIntent,
                showBack = !state.layout.isListDetail,
            )

        SyncCenterPane.Recovery ->
            SyncCenterRecoveryShell(
                session = ready.session,
                onCancel = { onIntent(SyncCenterIntent.CancelSession) },
            )
    }
}


@Composable
private fun SyncCenterErrorPane(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("sync_center_error"),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.sync_center_error, message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onRetry,
            modifier =
                Modifier
                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                    .testTag("sync_center_retry"),
        ) {
            Text(stringResource(R.string.sync_center_retry))
        }
    }
}

@Composable
private fun SyncCenterEmptyPrompt() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("sync_center_idle"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.sync_center_idle_prompt),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
