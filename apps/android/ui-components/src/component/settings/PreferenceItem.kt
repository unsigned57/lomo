package com.lomo.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.util.LocalAppHapticFeedback

internal const val DISABLED_PREFERENCE_ALPHA = 0.56f

/**
 * Settings row that navigates or opens a value picker.
 *
 * The headline carries body-large's role with a medium weight so it stays distinguishable from the
 * supporting value underneath. A row without [onClick] renders as a read-only fact (the current
 * version, for example) and never draws a chevron it cannot honour.
 */
@Composable
fun PreferenceItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMinLines: Int = 1,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val haptic = LocalAppHapticFeedback.current
    val clickable = enabled && onClick != null
    ListItem(
        content = { Text(text = title, fontWeight = FontWeight.Medium) },
        supportingContent =
        subtitle?.let {
            {
                Text(
                    text = it,
                    minLines = subtitleMinLines,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = icon?.let { image -> { SettingsLeadingIcon(icon = image, checked = false) } },
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
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_PREFERENCE_ALPHA)
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
    )
}
