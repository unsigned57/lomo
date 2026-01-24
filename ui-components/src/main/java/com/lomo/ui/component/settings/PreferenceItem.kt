package com.lomo.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

@Composable
fun PreferenceItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = title) } },
        modifier =
            Modifier.clickable {
                haptic.medium()
                onClick()
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
