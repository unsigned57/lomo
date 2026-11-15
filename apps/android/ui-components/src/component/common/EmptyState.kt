package com.lomo.ui.component.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec

/**
 * M3 Expressive empty state: the icon sits in a tonal MaterialShapes container, the title uses
 * the emphasized headline style, and the description keeps full on-surface-variant contrast
 * instead of a washed-out alpha.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(
    icon: LomoIconSpec,
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
            Box(
                modifier =
                Modifier
                    .size(EmptyStateTokens.IconContainerSize)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LomoIcon(
                    icon = icon,
                    contentDescription = title,
                    modifier = Modifier.size(EmptyStateTokens.IconSize),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = EmptyStateTokens.TitleTopPadding),
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = EmptyStateTokens.DescriptionTopPadding),
                textAlign = TextAlign.Center,
            )

            if (action != null) {
                Spacer(Modifier.height(EmptyStateTokens.ActionSpacing))
                action()
            }
        }
    }
}
