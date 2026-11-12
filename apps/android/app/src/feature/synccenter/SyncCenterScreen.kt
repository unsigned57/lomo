package com.lomo.app.feature.synccenter

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoButton
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion

/**
 * Sync Center Compose shell.
 *
 * Registered in production navigation (NavRoute.SyncCenter). Session progress and conflict
 * pages project the durable Rust cycle/conflict records; cancel persists a durable request.
 * Phone: single-column pane routes. Expanded width: list-detail dual pane for conflicts.
 *
 * M3 Expressive chrome: a medium flexible top app bar whose subtitle carries the active
 * backend, and one connected ButtonGroup that owns pane selection.
 */

internal val SyncCenterMinTouchTarget = 48.dp

@Composable
fun SyncCenterRoute(viewModel: SyncCenterViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val isListDetail =
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    LaunchedEffect(isListDetail) {
        viewModel.setListDetail(isListDetail)
    }

    SyncCenterScreen(
        state = uiState,
        onIntent = { intent -> viewModel.applyScreenIntent(intent) },
        onClose = onClose,
        modifier = modifier,
    )
}

private fun SyncCenterViewModel.applyScreenIntent(intent: SyncCenterIntent) {
    when (intent) {
        is SyncCenterIntent.Open -> open(intent.workspaceRoot, intent.isListDetail)
        SyncCenterIntent.Refresh -> refresh()
        SyncCenterIntent.LoadMoreConflicts -> loadMoreConflicts()
        is SyncCenterIntent.SelectConflict -> selectConflict(intent.path)
        SyncCenterIntent.ClearSelection -> clearSelection()
        is SyncCenterIntent.SetResolutionKind -> setResolutionKind(intent.path, intent.kind)
        is SyncCenterIntent.SetMergedDraft -> setMergedDraft(intent.path, intent.draft)
        SyncCenterIntent.ApplyResolutions -> applyResolutions()
        SyncCenterIntent.NavigateOverview -> navigateOverview()
        SyncCenterIntent.NavigateConflicts -> navigateConflicts()
        SyncCenterIntent.NavigateRecovery -> navigateRecovery()
        is SyncCenterIntent.SetListDetail -> setListDetail(intent.isListDetail)
        SyncCenterIntent.CancelSession -> cancelSession()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCenterScreen(
    state: SyncCenterUiState,
    onIntent: (SyncCenterIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val ready = state.load as? SyncCenterLoadState.Ready
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    Scaffold(
        modifier =
        modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag(BenchmarkAnchorContract.SYNC_CENTER_ROOT),
        topBar = {
            SyncCenterTopBar(
                backendName = ready?.run { config.backend.name },
                scrollBehavior = scrollBehavior,
                onClose = {
                    haptic.medium()
                    onClose()
                },
                onRefresh = { onIntent(SyncCenterIntent.Refresh) },
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
                onIntent = onIntent,
            )
            Box(
                modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        horizontal = AppSpacing.Medium,
                        vertical = AppSpacing.MediumSmall,
                    ),
            ) {
                SyncCenterPaneHost(
                    load = state.load,
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncCenterTopBar(
    backendName: String?,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                Text(
                    text = stringResource(R.string.sync_center_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                if (backendName != null) {
                    Surface(
                        shape = AppShapes.Full,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = backendName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier =
                            Modifier.padding(
                                horizontal = AppSpacing.Small,
                                vertical = 2.dp,
                            ),
                        )
                    }
                }
            }
        },
        navigationIcon = {
            LomoIconButton(
                onClick = onClose,
                modifier =
                Modifier
                    .defaultMinSize(
                        minWidth = SyncCenterMinTouchTarget,
                        minHeight = SyncCenterMinTouchTarget,
                    )
                    .semantics {
                        contentDescription = "close"
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
            LomoIconButton(
                onClick = onRefresh,
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
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun SyncCenterPaneHost(
    load: SyncCenterLoadState,
    state: SyncCenterUiState,
    onIntent: (SyncCenterIntent) -> Unit,
) {
    when (load) {
        SyncCenterLoadState.Idle ->
            EmptyState(
                icon = Icons.Outlined.SettingsBackupRestore,
                title = stringResource(R.string.sync_center_idle_title),
                description = stringResource(R.string.sync_center_idle_prompt),
                modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("sync_center_idle"),
            )

        SyncCenterLoadState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ExpressiveContainedLoadingIndicator(
                    modifier = Modifier.testTag("sync_center_loading"),
                )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncCenterNavTabs(pane: SyncCenterPane, onIntent: (SyncCenterIntent) -> Unit) {
    val tabs =
        listOf(
            SyncCenterTab(
                icon = Icons.Outlined.Dashboard,
                labelRes = R.string.sync_center_tab_overview,
                selected = pane == SyncCenterPane.Overview,
                testTag = "sync_center_tab_overview",
                navigate = SyncCenterIntent.NavigateOverview,
            ),
            SyncCenterTab(
                icon = Icons.AutoMirrored.Outlined.CompareArrows,
                labelRes = R.string.sync_center_tab_conflicts,
                selected = pane == SyncCenterPane.Conflicts || pane == SyncCenterPane.ConflictDetail,
                testTag = BenchmarkAnchorContract.SYNC_CENTER_TAB_CONFLICTS,
                navigate = SyncCenterIntent.NavigateConflicts,
            ),
            SyncCenterTab(
                icon = Icons.Outlined.SettingsBackupRestore,
                labelRes = R.string.sync_center_tab_recovery,
                selected = pane == SyncCenterPane.Recovery,
                testTag = "sync_center_tab_recovery",
                navigate = SyncCenterIntent.NavigateRecovery,
            ),
        )
    // The ButtonGroupScope content lambda is not composable, so labels resolve upfront.
    val labels = tabs.map { tab -> stringResource(tab.labelRes) }
    Surface(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppSpacing.Medium,
                vertical = AppSpacing.Small,
            ),
        shape = AppShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ButtonGroup(
            overflowIndicator = { menuState ->
                ButtonGroupDefaults.OverflowIndicator(menuState)
            },
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                toggleableItem(
                    checked = tab.selected,
                    onCheckedChange = { checked ->
                        if (checked) onIntent(tab.navigate)
                    },
                    label = labels[index],
                    // ButtonGroupScope.toggleableItem owns its own button; the pane anchor rides the
                    // icon slot, the same way the memo filter group anchors its sort options.
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier =
                            Modifier
                                .size(SyncCenterTabIconSize)
                                .testTag(tab.testTag),
                        )
                    },
                    weight = 1f,
                )
            }
        }
    }
}

private class SyncCenterTab(
    val icon: ImageVector,
    val labelRes: Int,
    val selected: Boolean,
    val testTag: String,
    val navigate: SyncCenterIntent,
)

private val SyncCenterTabIconSize = 18.dp

@Composable
private fun SyncCenterReadyBody(
    state: SyncCenterUiState,
    ready: SyncCenterLoadState.Ready,
    onIntent: (SyncCenterIntent) -> Unit,
) {
    val layoutMotion = LocalLomoMotion.current.layout
    AnimatedContent(
        targetState = state.pane,
        transitionSpec = {
            (
                fadeIn(layoutMotion.defaultEffectsSpec()) +
                    scaleIn(initialScale = 0.97f, animationSpec = layoutMotion.defaultSpatialSpec())
                ) togetherWith fadeOut(layoutMotion.fastEffectsSpec())
        },
        label = "SyncCenterPaneTransition",
    ) { pane ->
        when (pane) {
            SyncCenterPane.Overview ->
                SyncCenterOverviewPane(
                    config = ready.config,
                    session = ready.session,
                    conflictPage = ready.conflictPage,
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
}

@Composable
private fun SyncCenterErrorPane(message: String, onRetry: () -> Unit) {
    Surface(
        modifier =
        Modifier
            .fillMaxSize()
            .testTag("sync_center_error"),
        shape = AppShapes.Large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.SyncProblem,
                contentDescription = null,
                modifier = Modifier.size(SyncCenterErrorIconSize),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.sync_center_error, message),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            LomoButton(
                onClick = onRetry,
                modifier =
                Modifier
                    .defaultMinSize(minHeight = SyncCenterMinTouchTarget)
                    .testTag("sync_center_retry"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.size(AppSpacing.Small))
                Text(stringResource(R.string.sync_center_retry))
            }
        }
    }
}

private val SyncCenterErrorIconSize = 40.dp
