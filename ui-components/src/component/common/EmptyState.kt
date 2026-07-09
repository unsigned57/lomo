package com.lomo.ui.component.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(EmptyStateTokens.ContentPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier =
                    Modifier
                        .size(EmptyStateTokens.IconSize)
                        .padding(bottom = EmptyStateTokens.IconBottomPadding),
                tint = EmptyStateTokens.iconColor(MaterialTheme.colorScheme),
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = EmptyStateTokens.descriptionColor(MaterialTheme.colorScheme),
                modifier = Modifier.padding(top = EmptyStateTokens.DescriptionTopPadding),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            if (action != null) {
                Spacer(Modifier.height(EmptyStateTokens.ActionSpacing))
                action()
            }
        }
    }
}
