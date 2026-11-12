package com.lomo.ui.component.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes

/** Container edge of a settings row's leading icon (M3E compact badge proportion). */
private val LeadingIconContainerSize = 38.dp

/** M3E leading icon glyph size inside a tonal badge. */
private val LeadingIconGlyphSize = 20.dp

/**
 * Leading icon shared by every settings row.
 *
 * [checked] selects the active tonal pair (`primaryContainer` / `onPrimaryContainer`) so a switch
 * row reads its enabled state from the badge, while navigation and unchecked rows use a calm
 * `surfaceContainerHighest` badge with `onSurfaceVariant` glyph.
 */
@Composable
internal fun SettingsLeadingIcon(icon: ImageVector, checked: Boolean) {
    Surface(
        color =
        if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        shape = AppShapes.Medium,
    ) {
        Box(
            modifier = Modifier.size(LeadingIconContainerSize),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint =
                if (checked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(LeadingIconGlyphSize),
            )
        }
    }
}
