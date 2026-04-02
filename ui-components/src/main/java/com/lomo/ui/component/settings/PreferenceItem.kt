package com.lomo.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes

private const val DISABLED_PREFERENCE_ALPHA = 0.56f

@Composable
fun PreferenceItem(
    title: String,
    subtitle: String? = null,
    subtitleMinLines: Int = 1,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val clickable = enabled && onClick != null
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(text = it, minLines = subtitleMinLines) } },
        leadingContent =
            icon?.let { image ->
                {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = AppShapes.Medium,
                    ) {
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = image,
                                contentDescription = title,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
        trailingContent =
            if (showChevron && onClick != null) {
                {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                null
            },
        modifier =
            Modifier
                .alpha(if (enabled) 1f else DISABLED_PREFERENCE_ALPHA)
                .then(
                    if (clickable) {
                        Modifier.clickable {
                            haptic.medium()
                            onClick()
                        }
                    } else {
                        Modifier
                    },
                ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
