package com.lomo.ui.component.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.util.LocalAppHapticFeedback

@Composable
fun MemoActionSheet(
    state: MemoMenuState,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
    ) {
        // Quick Action Buttons Row (MD3 style chips)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            ActionChip(
                icon = Icons.Outlined.ContentCopy,
                label = stringResource(R.string.action_copy),
                onClick = {
                    haptic.medium()
                    onCopy()
                    onDismiss()
                },
            )
            ActionChip(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.action_share),
                onClick = {
                    haptic.medium()
                    onShare()
                    onDismiss()
                },
            )
            ActionChip(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.action_edit),
                onClick = {
                    haptic.medium()
                    onEdit()
                },
            )
            ActionChip(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.action_delete),
                isDestructive = true,
                onClick = {
                    haptic.heavy()
                    onDelete()
                },
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        // Info Section - Modern Card Style
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfoItem(label = stringResource(R.string.info_created), value = state.createdTime)
                InfoItem(label = stringResource(R.string.info_characters), value = "${state.wordCount}", alignment = Alignment.End)
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
) {
    val containerColor =
        if (isDestructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        }
    val contentColor =
        if (isDestructive) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Surface(
        onClick = onClick,
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.height(64.dp).width(72.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = alignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
