package com.lomo.ui.component.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback

private val GroupOuterCornerRadius = 20.dp
private val GroupInnerCornerRadius = 4.dp
private val GroupSegmentGap = 2.dp

/**
 * Registers settings rows and expandable regions without moving preference ownership into UI.
 */
class SettingsGroupScope internal constructor() {
    internal val nodes = mutableListOf<SettingsGroupNode>()

    /** Registers one settings row. */
    fun item(content: @Composable () -> Unit) {
        nodes += SettingsGroupNode.Segment(content)
    }

    /** Registers a set of rows that animates in/out together when [visible] toggles. */
    fun expandable(visible: Boolean, label: String, content: SettingsGroupScope.() -> Unit) {
        val inner = SettingsGroupScope().apply(content)
        nodes += SettingsGroupNode.Expansion(visible, label, inner.nodes.toList())
    }
}

internal sealed interface SettingsGroupNode {
    fun visibleCount(): Int

    class Segment(val content: @Composable () -> Unit) : SettingsGroupNode {
        override fun visibleCount(): Int = 1
    }

    class Expansion(val visible: Boolean, val label: String, val nodes: List<SettingsGroupNode>) : SettingsGroupNode {
        override fun visibleCount(): Int = if (visible) nodes.sumOf { it.visibleCount() } else 0
    }
}

/**
 * Renders a Material 3 Expressive segmented settings group where each visible item sits in its
 * own tonal surface segment separated by [GroupSegmentGap] (`2.dp`), with `20.dp` outer group
 * corners and `4.dp` inner connected corners.
 */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    val totalVisibleRows = scope.nodes.sumOf { it.visibleCount() }
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                Modifier.padding(
                    start = 12.dp,
                    bottom = AppSpacing.Small,
                ),
            )
        }
        RenderSettingsGroupNodes(
            nodes = scope.nodes,
            initialPrecedingVisibleRows = 0,
            totalVisibleRows = totalVisibleRows,
        )
    }
}

@Composable
private fun RenderSettingsGroupNodes(
    nodes: List<SettingsGroupNode>,
    initialPrecedingVisibleRows: Int,
    totalVisibleRows: Int,
) {
    var precedingRows = initialPrecedingVisibleRows
    nodes.forEach { node ->
        val nodeVisibleCount = node.visibleCount()
        val hasPrecedingRows = precedingRows > 0
        val hasFollowingRows = (precedingRows + nodeVisibleCount) < totalVisibleRows
        when (node) {
            is SettingsGroupNode.Segment -> {
                if (hasPrecedingRows) {
                    Spacer(modifier = Modifier.height(GroupSegmentGap))
                }
                SettingsSegmentSurface(
                    isFirst = !hasPrecedingRows,
                    isLast = !hasFollowingRows,
                    content = node.content,
                )
            }

            is SettingsGroupNode.Expansion -> {
                val layoutMotion = LocalLomoMotion.current.layout
                val expansionStartPreceding = precedingRows
                AnimatedVisibility(
                    visible = node.visible,
                    enter = expandVertically(layoutMotion.defaultSpatialSpec()) +
                        fadeIn(layoutMotion.defaultEffectsSpec()),
                    exit = shrinkVertically(layoutMotion.fastSpatialSpec()) +
                        fadeOut(layoutMotion.fastEffectsSpec()),
                    label = node.label,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        RenderSettingsGroupNodes(
                            nodes = node.nodes,
                            initialPrecedingVisibleRows = expansionStartPreceding,
                            totalVisibleRows = totalVisibleRows,
                        )
                    }
                }
            }
        }
        precedingRows += nodeVisibleCount
    }
}

@Composable
private fun SettingsSegmentSurface(isFirst: Boolean, isLast: Boolean, content: @Composable () -> Unit) {
    val componentMotion = LocalLomoMotion.current.component
    val topRadius by animateDpAsState(
        targetValue = if (isFirst) GroupOuterCornerRadius else GroupInnerCornerRadius,
        animationSpec = componentMotion.fastSpatialSpec(),
        label = "SettingsSegmentTopRadius",
    )
    val bottomRadius by animateDpAsState(
        targetValue = if (isLast) GroupOuterCornerRadius else GroupInnerCornerRadius,
        animationSpec = componentMotion.fastSpatialSpec(),
        label = "SettingsSegmentBottomRadius",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape =
        RoundedCornerShape(
            topStart = topRadius,
            topEnd = topRadius,
            bottomStart = bottomRadius,
            bottomEnd = bottomRadius,
        ),
    ) {
        content()
    }
}

/**
 * Settings row that toggles a preference.
 *
 * The row owns the toggle. The trailing [Switch] is presentation only (`onCheckedChange = null`),
 * so the whole row is one target that reports its checked state instead of a row that swallows
 * taps for a second, separately focusable control.
 */
@Composable
fun SwitchPreferenceItem(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptic = LocalAppHapticFeedback.current
    ListItem(
        content = { Text(text = title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(text = it) } },
        leadingContent = { SettingsLeadingIcon(icon = icon, checked = checked) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        modifier =
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_PREFERENCE_ALPHA)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptic.medium()
                    onCheckedChange(it)
                },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
