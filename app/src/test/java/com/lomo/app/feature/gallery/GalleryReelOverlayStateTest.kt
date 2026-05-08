package com.lomo.app.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: GalleryReelOverlayAnchor state transition.
 * - Behavior focus: back-button anchor fallback before route pop.
 * - Observable outcomes: next anchor selected for each current anchor.
 * - Red phase: Fails before the feature because GalleryReelOverlayAnchor and nextAnchorOnBack do not exist.
 * - Excludes: Compose draggable gestures, pixel positions, system back dispatcher wiring.
 */
class GalleryReelOverlayStateTest {
    @Test
    fun `nextAnchorOnBack collapses expanded overlay before hiding it`() {
        assertEquals(
            GalleryReelOverlayAnchor.Collapsed,
            nextAnchorOnBack(GalleryReelOverlayAnchor.Expanded),
        )
        assertEquals(
            GalleryReelOverlayAnchor.Hidden,
            nextAnchorOnBack(GalleryReelOverlayAnchor.Collapsed),
        )
    }

    @Test
    fun `nextAnchorOnBack keeps hidden overlay hidden so route can pop`() {
        assertEquals(
            GalleryReelOverlayAnchor.Hidden,
            nextAnchorOnBack(GalleryReelOverlayAnchor.Hidden),
        )
    }
}

