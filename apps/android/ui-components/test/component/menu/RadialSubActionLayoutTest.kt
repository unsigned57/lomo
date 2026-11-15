package com.lomo.ui.component.menu

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: computeRadialArcLayout pure polar geometry helper for sub-action fan-out.
 * - Owning layer: ui-components presentation.
 * - Priority tier: P1.
 * - Capability: place 1..N sub-actions on a symmetric upward arc centered over the bottom anchor
 *   pill, clamped cleanly inside viewport bounds on both narrow portrait and short landscape windows.
 *
 * Scenarios:
 * - Given zero items or empty viewport bounds, when computed, then an empty item list is returned.
 * - Given 2 sub-actions on a phone-width viewport, when computed, then they sit symmetrically on
 *   left and right above the anchor pill.
 * - Given 3 sub-actions on a phone-width viewport, when computed, then left and right items mirror
 *   across X=0 and the middle item sits highest at X=0 with clear vertical separation above the pill.
 * - Given a narrow portrait or short landscape viewport, when computed, then every item's bounding
 *   extent remains inside the viewport margins without clipping.
 *
 * Observable outcomes: computed center anchor coordinates and relative (offsetXPx, offsetYPx) items.
 *
 * TDD proof:
 * - RED: fails to compile until computeRadialArcLayout, RadialArcViewport, RadialArcSpec, and
 *   RadialArcLayout exist in ActionSheetUi.kt.
 * - GREEN: passes after ActionSheetUi.kt implements computeRadialArcLayout.
 *
 * Excludes: Compose Popup rendering, blur effects, and spring animation timing.
 */
class RadialSubActionLayoutTest : UiComponentsFunSpec() {
    init {
        test("given zero items or invalid viewport when computed then returns empty items") {
            val spec = defaultSpec(preferredRadiusPx = 396f)
            val emptyItems =
                computeRadialArcLayout(
                    itemCount = 0,
                    viewport = RadialArcViewport(1080f, 2400f, topInsetPx = 72f, bottomInsetPx = 72f),
                    spec = spec,
                )
            emptyItems.items.shouldBeEmpty()

            val zeroViewport =
                computeRadialArcLayout(
                    itemCount = 3,
                    viewport = RadialArcViewport(0f, 0f, topInsetPx = 0f, bottomInsetPx = 0f),
                    spec = spec,
                )
            zeroViewport.items.shouldBeEmpty()
        }

        test("given two sub-actions when computed then left and right mirror symmetrically above anchor") {
            val layout =
                computeRadialArcLayout(
                    itemCount = 2,
                    viewport = RadialArcViewport(1080f, 2400f, topInsetPx = 72f, bottomInsetPx = 72f),
                    spec = defaultSpec(preferredRadiusPx = 360f),
                )

            layout.items.shouldHaveSize(2)
            assertSoftly(layout) {
                centerXPx shouldBe 540f
                val left = items[0]
                val right = items[1]
                left.offsetXPx shouldBeLessThan 0f
                right.offsetXPx.shouldBeWithinPercentageOf(-left.offsetXPx, 0.01)
                right.offsetYPx.shouldBeWithinPercentageOf(left.offsetYPx, 0.01)
                val anchorTopY = anchorCenterYPx - 156f / 2f
                (anchorCenterYPx + left.offsetYPx + 156f) shouldBeLessThan anchorTopY
            }
        }

        test("given three sub-actions on standard phone then outer items mirror and center item peaks") {
            val layout =
                computeRadialArcLayout(
                    itemCount = 3,
                    viewport = RadialArcViewport(1080f, 2400f, topInsetPx = 72f, bottomInsetPx = 72f),
                    spec = defaultSpec(preferredRadiusPx = 396f),
                )

            layout.items.shouldHaveSize(3)
            assertSoftly(layout) {
                val left = items[0]
                val top = items[1]
                val right = items[2]
                left.offsetXPx shouldBeLessThan 0f
                top.offsetXPx.shouldBeWithinPercentageOf(0f, 0.01)
                right.offsetXPx.shouldBeWithinPercentageOf(-left.offsetXPx, 0.01)
                left.offsetYPx.shouldBeWithinPercentageOf(right.offsetYPx, 0.01)
                top.offsetYPx shouldBeLessThan left.offsetYPx
                val anchorTopY = anchorCenterYPx - 156f / 2f
                (anchorCenterYPx + left.offsetYPx + 156f) shouldBeLessThan anchorTopY
            }
        }

        test("given narrow width and short landscape height then arc scales down inside safe bounds") {
            val spec =
                RadialArcSpec(
                    anchorBottomMarginPx = 48f,
                    anchorHeightPx = 120f,
                    itemHalfSpanPx = 144f,
                    itemTopExtentPx = 84f,
                    itemBottomExtentPx = 140f,
                    preferredRadiusPx = 396f,
                    edgeMarginPx = 48f,
                )
            val layout =
                computeRadialArcLayout(
                    itemCount = 3,
                    viewport = RadialArcViewport(900f, 720f, topInsetPx = 48f, bottomInsetPx = 48f),
                    spec = spec,
                )

            layout.items.shouldHaveSize(3)
            layout.items.forEach { item ->
                val itemCenterX = layout.centerXPx + item.offsetXPx
                val itemCenterY = layout.anchorCenterYPx + item.offsetYPx
                (itemCenterX - spec.itemHalfSpanPx) shouldBeGreaterThan (spec.edgeMarginPx - 0.1f)
                (itemCenterX + spec.itemHalfSpanPx) shouldBeLessThan (900f - spec.edgeMarginPx + 0.1f)
                (itemCenterY - spec.itemTopExtentPx) shouldBeGreaterThan (48f + spec.edgeMarginPx - 0.1f)
            }
        }
    }

    private fun defaultSpec(preferredRadiusPx: Float): RadialArcSpec = RadialArcSpec(
        anchorBottomMarginPx = 84f,
        anchorHeightPx = 156f,
        itemHalfSpanPx = 144f,
        itemTopExtentPx = 84f,
        itemBottomExtentPx = 156f,
        preferredRadiusPx = preferredRadiusPx,
        edgeMarginPx = 48f,
    )
}
