package com.lomo.app.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: BenchmarkMainScreenNavigationSnapshot readiness and drawer-mode helpers.
 * - Behavior focus: baseline harness main-screen readiness and back-navigation decisions across
 *   compact modal-drawer and expanded permanent-sidebar layouts.
 * - Observable outcomes: ready-state boolean, drawer-close decision, and drawer-open decision.
 * - Red phase: Fails before the fix because permanent-sidebar layouts expose drawer destinations
 *   immediately, causing the helper to treat the main screen as not ready and to close it with Back.
 * - Excludes: UiAutomator selectors, Compose rendering, and macrobenchmark execution timing.
 */
class BenchmarkMainScreenNavigationTest {
    @Test
    fun `compact layout with closed drawer is ready`() {
        assertTrue(
            BenchmarkMainScreenNavigationSnapshot(
                hasMainRoot = true,
                hasSearchButton = true,
                hasDrawerButton = true,
                hasDrawerDestinations = false,
            ).isMainScreenReady(),
        )
    }

    @Test
    fun `expanded layout with permanent sidebar is still ready`() {
        assertTrue(
            BenchmarkMainScreenNavigationSnapshot(
                hasMainRoot = true,
                hasSearchButton = true,
                hasDrawerButton = false,
                hasDrawerDestinations = true,
            ).isMainScreenReady(),
        )
    }

    @Test
    fun `open modal drawer should be closed with back`() {
        assertTrue(
            BenchmarkMainScreenNavigationSnapshot(
                hasMainRoot = true,
                hasSearchButton = true,
                hasDrawerButton = true,
                hasDrawerDestinations = true,
            ).shouldCloseDrawerWithBack(),
        )
    }

    @Test
    fun `permanent sidebar should not be closed with back`() {
        assertFalse(
            BenchmarkMainScreenNavigationSnapshot(
                hasMainRoot = true,
                hasSearchButton = true,
                hasDrawerButton = false,
                hasDrawerDestinations = true,
            ).shouldCloseDrawerWithBack(),
        )
    }

    @Test
    fun `closed modal drawer still requires drawer button to reveal destinations`() {
        assertTrue(
            BenchmarkMainScreenNavigationSnapshot(
                hasMainRoot = true,
                hasSearchButton = true,
                hasDrawerButton = true,
                hasDrawerDestinations = false,
            ).shouldOpenDrawerFromButton(),
        )
    }

    @Test
    fun `permanent sidebar must not wait for drawer button`() {
        assertFalse(
            BenchmarkMainScreenNavigationSnapshot(
                hasMainRoot = true,
                hasSearchButton = true,
                hasDrawerButton = false,
                hasDrawerDestinations = true,
            ).shouldOpenDrawerFromButton(),
        )
    }
}
