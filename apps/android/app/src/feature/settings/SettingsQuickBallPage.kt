package com.lomo.app.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.quickball.QuickBallActionSheet
import com.lomo.app.feature.quickball.icon
import com.lomo.app.feature.quickball.labelRes
import com.lomo.app.feature.quickball.quickBallIcons
import com.lomo.app.feature.quickball.quickBallLabels
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.QuickBallAction
import com.lomo.domain.model.QuickBallConfig
import com.lomo.domain.model.QuickBallGesture
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.GestureBall
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickBallSettingsPage(
    config: QuickBallConfig,
    feature: SettingsInteractionFeatureViewModel,
    onBack: () -> Unit,
) {
    var editingGesture by remember { mutableStateOf<QuickBallGesture?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.quick_ball_save_failed)
    val save: (suspend () -> Unit) -> Unit = { write ->
        saving = true
        scope.launch {
            try {
                runSuspendCatching { write() }.onFailure {
                    snackbar.showSnackbar(saveFailed)
                }
            } finally {
                saving = false
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier =
        Modifier
            .benchmarkAnchorRoot(BenchmarkAnchorContract.QUICK_BALL_ROOT)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.quick_ball_title),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        QuickBallSettingsBody(
            padding = padding,
            config = config,
            saving = saving,
            onSelectGesture = { editingGesture = it },
            onRequestReset = { confirmReset = true },
        )
    }

    editingGesture?.let { gesture ->
        QuickBallActionSheet(
            title = stringResource(gesture.labelRes()),
            actions = QuickBallAction.entries.toImmutableList(),
            selectedAction = config.action(gesture),
            onDismiss = { editingGesture = null },
            onSelect = { action ->
                editingGesture = null
                save { feature.updateQuickBallAction(gesture, action) }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            modifier = Modifier.benchmarkAnchorRoot(BenchmarkAnchorContract.QUICK_BALL_RESET_DIALOG),
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.quick_ball_restore)) },
            text = { Text(stringResource(R.string.quick_ball_restore_message)) },
            confirmButton = {
                LomoTextButton(onClick = {
                    confirmReset = false
                    save { feature.resetQuickBallConfig() }
                }, modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.QUICK_BALL_RESET_CONFIRM)) {
                    Text(stringResource(R.string.quick_ball_restore))
                }
            },
            dismissButton = {
                LomoTextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun QuickBallSettingsBody(
    padding: androidx.compose.foundation.layout.PaddingValues,
    config: QuickBallConfig,
    saving: Boolean,
    onSelectGesture: (QuickBallGesture) -> Unit,
    onRequestReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = AppSpacing.ScreenHorizontalPadding,
                vertical = AppSpacing.MediumSmall,
            ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        QuickBallPreview(config)
        QuickBallHelpBanner()
        SettingsGroup(title = stringResource(R.string.quick_ball_subtitle)) {
            QuickBallGesture.entries.forEach { gesture ->
                item {
                    QuickBallGestureBindingRow(
                        gesture = gesture,
                        action = config.action(gesture),
                        enabled = !saving,
                        onClick = if (gesture == QuickBallGesture.TAP) null else ({ onSelectGesture(gesture) }),
                    )
                }
            }
        }
        LomoFilledTonalButton(
            onClick = onRequestReset,
            enabled = !saving,
            modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .benchmarkAnchor(BenchmarkAnchorContract.QUICK_BALL_RESET),
        ) {
            Icon(
                imageVector = Icons.Outlined.Restore,
                contentDescription = null,
                modifier = Modifier.padding(end = AppSpacing.Small),
            )
            Text(
                text = stringResource(R.string.quick_ball_restore),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QuickBallHelpBanner() {
    Surface(
        shape = AppShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.MediumSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.quick_ball_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickBallGestureBindingRow(
    gesture: QuickBallGesture,
    action: QuickBallAction,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val haptic = LocalAppHapticFeedback.current
    val clickable = enabled && onClick != null
    val isActiveAction = action != QuickBallAction.NONE
    val isLocked = gesture == QuickBallGesture.TAP

    ListItem(
        leadingContent = {
            QuickBallBindingGestureIcon(gesture, isActiveAction, isLocked)
        },
        supportingContent = {
            Text(
                text = stringResource(action.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color =
                if (isActiveAction) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
            ) {
                Surface(
                    shape = AppShapes.Full,
                    color =
                    if (isActiveAction) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = action.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint =
                            if (isActiveAction) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (isLocked) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier =
        Modifier
            .alpha(if (enabled) 1f else QUICK_BALL_DISABLED_ALPHA)
            .then(
                if (clickable) {
                    Modifier.clickable(role = Role.Button) {
                        haptic.medium()
                        onClick()
                    }
                } else {
                    Modifier
                },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = stringResource(gesture.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickBallPreview(config: QuickBallConfig) {
    var recognized by remember { mutableStateOf<QuickBallGesture?>(null) }
    val labels = quickBallLabels(config)

    Surface(
        shape = AppShapes.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            // Fixed-height stage status header so gesture recognition never jumps card height
            QuickBallPreviewStatus(recognized, labels)

            // Up direction pill
            QuickBallCompassDirectionPill(
                action = config.up,
                text = "↑ " + stringResource(config.up.labelRes()),
                isPrimaryAxis = true,
            )

            // Horizontal axis + central GestureBall with concentric M3E radar rings
            QuickBallPreviewCompass(config, labels) { recognized = it }

            // Down direction pill
            QuickBallCompassDirectionPill(
                action = config.down,
                text = "↓ " + stringResource(config.down.labelRes()),
                isPrimaryAxis = true,
            )

            // Footer metadata bar for Tap & Long Press bindings
            QuickBallPreviewBindings(config, labels)
        }
    }
}

@Composable
private fun QuickBallCompassDirectionPill(
    action: QuickBallAction,
    text: String,
    isPrimaryAxis: Boolean,
    alignEnd: Boolean = false,
) {
    val isActive = action != QuickBallAction.NONE
    Surface(
        shape = AppShapes.Full,
        color =
        when {
            isPrimaryAxis && isActive -> MaterialTheme.colorScheme.secondaryContainer
            isActive -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!alignEnd) {
                Icon(
                    imageVector = action.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint =
                    when {
                        isPrimaryAxis && isActive -> MaterialTheme.colorScheme.onSecondaryContainer
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }
            Text(
                text = text,
                style = if (isPrimaryAxis) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color =
                when {
                    isPrimaryAxis && isActive -> MaterialTheme.colorScheme.onSecondaryContainer
                    isActive -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.outline
                },
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (alignEnd) {
                Icon(
                    imageVector = action.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint =
                    if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

private const val PREVIEW_SIDE_LABEL_FRACTION = 0.35f

@Composable
private fun QuickBallBindingGestureIcon(gesture: QuickBallGesture, isActiveAction: Boolean, isLocked: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color =
        if (isActiveAction && !isLocked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = gesture.icon(),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint =
                if (isActiveAction && !isLocked) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun QuickBallPreviewStatus(
    recognized: QuickBallGesture?,
    labels: kotlinx.collections.immutable.ImmutableMap<QuickBallGesture, String>,
) {
    val layoutMotion = LocalLomoMotion.current.layout

    Surface(
        shape = AppShapes.Full,
        color =
        if (recognized != null) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = AppSpacing.MediumSmall, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = recognized,
                transitionSpec = {
                    fadeIn(layoutMotion.fastEffectsSpec()) togetherWith
                        fadeOut(layoutMotion.fastEffectsSpec())
                },
                label = "QuickBallPreviewStatus",
            ) { gesture ->
                if (gesture != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.quick_ball_preview_result, labels.getValue(gesture)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.quick_ball_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickBallPreviewCompass(
    config: QuickBallConfig,
    labels: kotlinx.collections.immutable.ImmutableMap<QuickBallGesture, String>,
    onGesture: (QuickBallGesture) -> Unit,
) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 124.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(PREVIEW_SIDE_LABEL_FRACTION),
            contentAlignment = Alignment.CenterStart,
        ) {
            QuickBallCompassDirectionPill(
                action = config.left,
                text = "← " + stringResource(config.left.labelRes()),
                isPrimaryAxis = false,
            )
        }

        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier =
                Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)),
            )
            Box(
                modifier =
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)),
            )
            GestureBall(
                labels = labels,
                icons = quickBallIcons(config),
                onGesture = onGesture,
                modifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.QUICK_BALL_PREVIEW),
            )
        }

        Box(
            modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(PREVIEW_SIDE_LABEL_FRACTION),
            contentAlignment = Alignment.CenterEnd,
        ) {
            QuickBallCompassDirectionPill(
                action = config.right,
                text = stringResource(config.right.labelRes()) + " →",
                isPrimaryAxis = false,
                alignEnd = true,
            )
        }
    }
}

@Composable
private fun QuickBallPreviewBindings(
    config: QuickBallConfig,
    labels: kotlinx.collections.immutable.ImmutableMap<QuickBallGesture, String>,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.ExtraSmall),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = AppShapes.Full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text =
                    stringResource(
                        R.string.quick_ball_binding,
                        stringResource(QuickBallGesture.TAP.labelRes()),
                        stringResource(config.action(QuickBallGesture.TAP).labelRes()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            shape = AppShapes.Full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.RadioButtonChecked,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = labels.getValue(QuickBallGesture.LONG_PRESS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val QUICK_BALL_DISABLED_ALPHA = 0.56f
