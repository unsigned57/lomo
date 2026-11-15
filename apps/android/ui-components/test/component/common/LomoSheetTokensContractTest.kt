package com.lomo.ui.component.common

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.menu.ActionItemPlacement
import com.lomo.ui.component.menu.ActionItemUi
import com.lomo.ui.component.menu.ActionSheetTokens
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.testing.UiComponentsFunSpec
import com.lomo.ui.theme.AppShapes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * Capability: unified modal bottom sheet canvas, tile/capsule corner hierarchy, and action sheet
 * spacing/contrast tokens; owner: ui-components design tokens; priority: P1.
 *
 * Test Change Justification:
 * - Reason category: redesign of memo action sheet from giant 32dp 3-across tiles + 56dp rows to
 *   compact v1.6.2-proportioned 4-across tiles, aligned bottom capsules, and bottom metadata card.
 * - Old behavior/assertion being replaced: ActionSheetTokens.TileShape == AppShapes.ExtraLargeIncreased,
 *   ActionSheetTokens.SectionSpacing == 20.dp, ActionSheetTokens.RowIconLabelSpacing == 20.dp.
 * - Why old assertion is no longer correct: 32dp tile corners and 20dp list row spacing belonged
 *   to the giant 3-tile + list layout and look bulbous/stretched on 4-across 68dp tiles; tiles now
 *   unify on LomoSheetTokens.TileShape (AppShapes.LargeIncreased) and Row* tokens were deleted.
 * - Coverage preserved by: still locks concentric sheet/tile/capsule hierarchy, container contrast,
 *   neutral/highlighted/destructive colors, and now locks infoCardColor + TileHeight + MaxTileCount.
 * - Why this is not fitting the test to the implementation: asserts unified token invariants prior
 *   to editing ActionSheetTokens.kt.
 *
 * Scenarios:
 * - Given any ColorScheme (including OLED pure black), when resolving LomoSheetTokens sheet and tile
 *   surfaces, then the sheet uses surfaceContainerLow (or Color.Black for OLED dark) with 0.dp tonal
 *   elevation, neutral tiles use surfaceContainerHigh, and info cards use surfaceContainer.
 * - Given tile/block button shape tokens, when reading LomoButtonDefaults.tileShapes(),
 *   LomoButtonDefaults.capsuleShapes(), and ActionSheetTokens, then sheet tiles and action-sheet
 *   tiles standardize on AppShapes.LargeIncreased -> AppShapes.Medium matching SheetShape cleanly.
 * - Given an action item in neutral, highlighted, or destructive state, when resolving
 *   ActionSheetTokens.tileColors, then distinct container/content tokens are returned.
 *
 * Observable outcomes: token dimensions, corner shapes, colorScheme surface mappings, and state differentiation.
 * TDD proof: RED while ActionSheetTokens keeps MaxTileCount = 3, ExtraLargeIncreased tiles, Row*
 * tokens, and lacks infoCardColor.
 * Production scope: apps/android/ui-components/src/component/common/LomoSheetTokens.kt
 * Production scope: apps/android/ui-components/src/component/menu/ActionSheetTokens.kt
 * Excludes: OEM system bar rendering and physical screen color calibration.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class LomoSheetTokensContractTest : UiComponentsFunSpec() {
    init {
        test("sheet tokens enforce surfaceContainerLow canvas and zero elevation across light, dark, and oled") {
            val light = lightColorScheme()
            val oledDark =
                darkColorScheme().copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceContainerLowest = Color.Black,
                    surfaceContainerLow = Color.Black,
                    surfaceContainerHigh = Color(0xFF1F1F1F),
                )

            LomoSheetTokens.SheetShape shouldBe AppShapes.ExtraLargeIncreased
            LomoSheetTokens.TileShape shouldBe AppShapes.LargeIncreased
            LomoSheetTokens.TilePressedShape shouldBe AppShapes.Medium
            LomoSheetTokens.CapsuleShape shouldBe AppShapes.LargeIncreased
            LomoSheetTokens.CapsulePressedShape shouldBe AppShapes.Medium
            LomoSheetTokens.TonalElevation shouldBe 0.dp

            LomoSheetTokens.containerColor(light) shouldBe light.surfaceContainerLow
            LomoSheetTokens.tileSurfaceColor(light) shouldBe light.surfaceContainerHigh
            LomoSheetTokens.containerColor(oledDark) shouldBe Color.Black
            LomoSheetTokens.tileSurfaceColor(oledDark) shouldBe oledDark.surfaceContainerHigh
            LomoSheetTokens.tileSurfaceColor(light) shouldNotBe LomoSheetTokens.containerColor(light)
        }

        test("button tile and capsule shape helpers standardize expressive resting and pressed corners") {
            val tileShapes = LomoButtonDefaults.tileShapes()
            tileShapes.shape shouldBe AppShapes.LargeIncreased
            tileShapes.pressedShape shouldBe AppShapes.Medium

            val capsuleShapes = LomoButtonDefaults.capsuleShapes()
            capsuleShapes.shape shouldBe AppShapes.LargeIncreased
            capsuleShapes.pressedShape shouldBe AppShapes.Medium

            val actionTileShapes = ActionSheetTokens.tileShapes()
            actionTileShapes.shape shouldBe LomoSheetTokens.TileShape
            actionTileShapes.pressedShape shouldBe LomoSheetTokens.TilePressedShape
        }

        test("action sheet tokens inherit unified tiles, compact heights, info card surface, and state colors") {
            ActionSheetTokens.SheetShape shouldBe LomoSheetTokens.SheetShape
            ActionSheetTokens.TileShape shouldBe LomoSheetTokens.TileShape
            ActionSheetTokens.CapsuleShape shouldBe LomoSheetTokens.CapsuleShape
            ActionSheetTokens.InfoCardShape shouldBe LomoSheetTokens.CapsuleShape
            ActionSheetTokens.MaxTileCount shouldBe 4
            ActionSheetTokens.TileHeight shouldBe 68.dp
            ActionSheetTokens.CapsuleMinHeight shouldBe 48.dp
            ActionSheetTokens.SectionSpacing shouldBe 12.dp

            val scheme = lightColorScheme()
            ActionSheetTokens.infoCardColor(scheme) shouldBe scheme.surfaceContainer
            val neutral = sampleAction(isHighlighted = false, isDestructive = false)
            val highlighted = sampleAction(isHighlighted = true, isDestructive = false)
            val destructive = sampleAction(isHighlighted = false, isDestructive = true)

            val neutralColors = ActionSheetTokens.tileColors(scheme, neutral)
            neutralColors.container shouldBe scheme.surfaceContainerHigh
            neutralColors.content shouldBe scheme.onSurface

            val highlightedColors = ActionSheetTokens.tileColors(scheme, highlighted)
            highlightedColors.container shouldBe scheme.primaryContainer
            highlightedColors.content shouldBe scheme.onPrimaryContainer

            val destructiveColors = ActionSheetTokens.tileColors(scheme, destructive)
            destructiveColors.container shouldBe scheme.errorContainer
            destructiveColors.content shouldBe scheme.onErrorContainer
        }
    }

    private fun sampleAction(isHighlighted: Boolean, isDestructive: Boolean): ActionItemUi = ActionItemUi(
        icon = LomoIcons.ContentCopy,
        label = "Copy",
        onClick = {},
        placement = ActionItemPlacement.Tile,
        isHighlighted = isHighlighted,
        isDestructive = isDestructive,
    )
}
