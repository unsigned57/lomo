package com.lomo.ui.component.stats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: HeatmapPopupPositionProvider
 * - Behavior focus: keep the heatmap date popup inside the sidebar anchor for visible trailing-week selections.
 * - Observable outcomes: calculated popup window coordinates stay within the anchor width instead of overflowing to the sidebar exterior.
 * - Red phase: Fails before the fix because a trailing-week selection uses the full heatmap content X offset, pushing the popup beyond the sidebar anchor.
 * - Excludes: Compose canvas rendering, popup enter/exit animations, and sidebar list wiring.
 */
class CalendarHeatmapPopupPositionProviderTest {
    @Test
    fun `calculatePosition keeps popup within sidebar anchor for trailing visible dates`() {
        val layout =
            HeatmapLayout(
                cellSizePx = 10f,
                spacingPx = 3f,
                monthLabelHeightPx = 14f,
                cornerRadiusPx = 2f,
                weekWidth = 13f,
                totalWeeks = 52,
                totalWidth = 676f,
                totalHeight = 105f,
                startDay = LocalDate.of(2025, 3, 31),
                today = LocalDate.of(2026, 3, 25),
                monthLabels = emptyList(),
                heatmapCells = emptyList(),
            )
        val hit =
            HeatmapCellHit(
                col = 50,
                row = 2,
                date = LocalDate.of(2026, 3, 18),
            )
        val anchorBounds = IntRect(left = 0, top = 0, right = 280, bottom = 120)
        val popupSize = IntSize(width = 112, height = 48)
        val provider =
            HeatmapPopupPositionProvider(
                contentOffset = heatmapPopupOffset(layout, hit),
                density = Density(1f),
            )

        val position =
            provider.calculatePosition(
                anchorBounds = anchorBounds,
                windowSize = IntSize(width = 1080, height = 2400),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = popupSize,
            )

        assertTrue(
            "Popup should stay inside the sidebar anchor, but x=${position.x} width=${popupSize.width} anchorRight=${anchorBounds.right}",
            position.x >= anchorBounds.left && position.x + popupSize.width <= anchorBounds.right,
        )
    }

    @Test
    fun `heatmapPopupOffset subtracts horizontal scroll from content x`() {
        val layout =
            HeatmapLayout(
                cellSizePx = 10f,
                spacingPx = 3f,
                monthLabelHeightPx = 14f,
                cornerRadiusPx = 2f,
                weekWidth = 13f,
                totalWeeks = 52,
                totalWidth = 676f,
                totalHeight = 105f,
                startDay = LocalDate.of(2025, 3, 31),
                today = LocalDate.of(2026, 3, 25),
                monthLabels = emptyList(),
                heatmapCells = emptyList(),
            )
        val hit =
            HeatmapCellHit(
                col = 50,
                row = 2,
                date = LocalDate.of(2026, 3, 18),
            )

        val popupOffset =
            heatmapPopupOffset(
                layout = layout,
                hit = hit,
                horizontalScrollOffsetPx = 416f,
            )

        assertEquals(239f, popupOffset.x, 0.001f)
        assertEquals(40f, popupOffset.y, 0.001f)
    }
}
