package com.lomo.ui.component.stats

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lomo.ui.theme.MotionTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarHeatmap(
    memoCountByDate: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()

    // Config: Compact layout similar to original
    val density = LocalDensity.current
    val cellSize = 10.dp
    val spacing = 3.dp
    val monthLabelHeight = 14.dp // Small label area

    val cellSizePx = with(density) { cellSize.toPx() }
    val spacingPx = with(density) { spacing.toPx() }
    val monthLabelHeightPx = with(density) { monthLabelHeight.toPx() }
    val cornerRadiusPx = with(density) { 2.dp.toPx() }

    // Colors
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) // Lighter for less visual weight
    val level1Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val level2Color = MaterialTheme.colorScheme.primaryContainer
    val level3Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val level4Color = MaterialTheme.colorScheme.primary

    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val densityScale = density.density
    val fontScale = density.fontScale
    val textPaint =
        remember(textColor, densityScale, fontScale) {
            Paint().apply {
                color = textColor
                textSize = with(density) { 9.sp.toPx() }
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.LEFT
            }
        }

    // Interaction State
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedCount by remember { mutableStateOf(0) }
    var popupOffset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier =
            modifier
                .padding(vertical = 4.dp),
        // Reduce vertical padding
        contentAlignment = Alignment.Center,
    ) {
        val availableWidth = constraints.maxWidth.toFloat()
        val weekWidth = cellSizePx + spacingPx
        // Calculate weeks to show
        val weeksToShow = ((availableWidth) / weekWidth).toInt().coerceAtLeast(1).coerceAtMost(52)

        // Calculate dimensions
        val totalWidth = weeksToShow * weekWidth
        val totalHeight = monthLabelHeightPx + 7 * (cellSizePx + spacingPx)

        // Date Logic
        // endDay is today. We want formatting to align such that today is in the last column, correct row.
        val daysToSubtract = (weeksToShow - 1) * 7 + today.dayOfWeek.value % 7
        val startDay = today.minusDays(daysToSubtract.toLong())

        Box(
            modifier =
                Modifier
                    .width(with(density) { totalWidth.toDp() })
                    .height(with(density) { totalHeight.toDp() }),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(weeksToShow) {
                            detectTapGestures { offset ->
                                val y = offset.y - monthLabelHeightPx

                                // If clicked on label area or outside bottom, clear selection
                                if (y < 0 || y > 7 * (cellSizePx + spacingPx)) {
                                    selectedDate = null
                                    return@detectTapGestures
                                }

                                val col = (offset.x / (cellSizePx + spacingPx)).toInt()
                                val row = (y / (cellSizePx + spacingPx)).toInt()

                                if (col in 0 until weeksToShow && row in 0..6) {
                                    // Fix date calculation:
                                    // startDay is Sunday.
                                    // row 0 is Sunday.
                                    // simple: startDay.plusWeeks(col).plusDays(row)
                                    // My previous complex logic was trying to compensate for something nonexistent.
                                    // Let's stick to the simpler logic which matched the drawing loop.
                                    val date = startDay.plusWeeks(col.toLong()).plusDays(row.toLong())

                                    if (!date.isAfter(today)) {
                                        if (selectedDate == date) {
                                            // Toggle off if clicking same date
                                            selectedDate = null
                                        } else {
                                            selectedDate = date
                                            selectedCount = memoCountByDate[date] ?: 0
                                            popupOffset =
                                                Offset(
                                                    col * (cellSizePx + spacingPx) + cellSizePx / 2,
                                                    monthLabelHeightPx + row * (cellSizePx + spacingPx),
                                                )
                                        }
                                    } else {
                                        selectedDate = null
                                    }
                                } else {
                                    selectedDate = null
                                }
                            }
                        },
            ) {
                // Draw Month Labels
                var currentMonth = -1
                for (week in 0 until weeksToShow) {
                    val dateOfWeekStart = startDay.plusWeeks(week.toLong())
                    val month = dateOfWeekStart.monthValue

                    if (month != currentMonth) {
                        // Avoid drawing label too close to the right edge if it's the very last week
                        if (week < weeksToShow - 2 || weeksToShow < 3) {
                            val monthName = dateOfWeekStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            drawContext.canvas.nativeCanvas.drawText(
                                monthName,
                                week * (cellSizePx + spacingPx),
                                monthLabelHeightPx - 4.dp.toPx(),
                                textPaint,
                            )
                        }
                        currentMonth = month
                    }
                }

                // Draw Grid
                for (week in 0 until weeksToShow) {
                    for (day in 0..6) {
                        val date = startDay.plusWeeks(week.toLong()).plusDays(day.toLong())
                        if (date.isAfter(today)) continue

                        val count = memoCountByDate[date] ?: 0
                        val color =
                            when {
                                count == 0 -> emptyColor
                                count <= 1 -> level1Color
                                count <= 3 -> level2Color
                                count <= 6 -> level3Color
                                else -> level4Color
                            }

                        val left = week * (cellSizePx + spacingPx)
                        val top = monthLabelHeightPx + day * (cellSizePx + spacingPx)

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = Size(cellSizePx, cellSizePx),
                            cornerRadius = CornerRadius(cornerRadiusPx),
                        )

                        // Highlight selected cell border
                        if (date == selectedDate) {
                            drawRoundRect(
                                color = level4Color,
                                topLeft = Offset(left, top),
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = CornerRadius(cornerRadiusPx),
                                style =
                                    androidx.compose.ui.graphics.drawscope
                                        .Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
            }

            // Tooltip using Popup with manual state handling for exit animations
            // We keep specific data to render even when selectedDate becomes null (during fade-out)
            var activePopupData by remember { mutableStateOf<Triple<LocalDate, Int, Offset>?>(null) }
            var isPopupVisible by remember { mutableStateOf(false) }

            // Sync state with selection changes
            LaunchedEffect(selectedDate) {
                if (selectedDate != null) {
                    val count = memoCountByDate[selectedDate] ?: 0
                    activePopupData = Triple(selectedDate!!, count, popupOffset)
                    isPopupVisible = true
                } else {
                    isPopupVisible = false
                }
            }

            // Only render Popup if there is meaningful data to show (either active or fading out)
            val currentData = activePopupData
            if (currentData != null) {
                // Use a transition to track the visibility state
                val transition = updateTransition(targetState = isPopupVisible, label = "PopupVisibility")

                // Remove the data (and thus the Popup) only when fully invisible
                LaunchedEffect(transition.currentState, transition.targetState) {
                    if (!transition.currentState && !transition.targetState) {
                        activePopupData = null
                    }
                }

                // If we are still animating or visible, show the popup
                if (transition.currentState || transition.targetState) {
                    val (date, count, offset) = currentData
                    val popupPositionProvider =
                        remember(offset, density) {
                            HeatmapPopupPositionProvider(offset, density)
                        }

                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = popupPositionProvider,
                        onDismissRequest = { selectedDate = null },
                    ) {
                        transition.AnimatedVisibility(
                            visible = { it },
                            enter =
                                fadeIn(
                                    animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
                                ) +
                                    scaleIn(
                                        initialScale = 0.8f,
                                        animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
                                    ),
                            exit =
                                fadeOut(
                                    animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
                                ) +
                                    scaleOut(
                                        targetScale = 0.8f,
                                        animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
                                    ),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 3.dp,
                                shadowElevation = 3.dp,
                                modifier = Modifier.padding(4.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = date.format(DateTimeFormatter.ofPattern("MMM d")),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$count memos",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class HeatmapPopupPositionProvider(
    private val contentOffset: Offset,
    private val density: androidx.compose.ui.unit.Density,
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val targetX = anchorBounds.left + contentOffset.x
        val targetY = anchorBounds.top + contentOffset.y

        // Center the popup horizontally relative to the target point
        val popupX = targetX.toInt() - (popupContentSize.width / 2)

        // Place popup above the target point
        val margin = with(density) { 8.dp.roundToPx() }
        val popupY = targetY.toInt() - popupContentSize.height - margin

        return androidx.compose.ui.unit
            .IntOffset(popupX, popupY)
    }
}
