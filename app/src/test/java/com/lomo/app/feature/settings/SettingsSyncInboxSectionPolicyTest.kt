package com.lomo.app.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: sync inbox settings section policy.
 * - Behavior focus: sync inbox should behave like other sync integrations by always exposing a
 *   tappable section header, while only gating the nested directory row behind the enabled state.
 * - Observable outcomes: header visibility/clickability and nested directory visibility booleans.
 * - Red phase: Fails before the fix because the sync inbox directory row lived in storage settings
 *   and became disabled/greyed out when the feature was off, so there was no sync-style section
 *   contract to keep the entry point interactive.
 * - Excludes: Compose animations, picker launching, and repository persistence.
 */
class SettingsSyncInboxSectionPolicyTest {
    @Test
    fun `collapsed section stays interactive when sync inbox is disabled`() {
        val policy = SyncInboxSectionPolicies.resolve(enabled = false)

        assertTrue(policy.showSectionHeader)
        assertTrue(policy.headerInteractive)
        assertFalse(policy.showDirectoryPreference)
    }

    @Test
    fun `expanded section shows directory preference when sync inbox is enabled`() {
        val policy = SyncInboxSectionPolicies.resolve(enabled = true)

        assertTrue(policy.showSectionHeader)
        assertTrue(policy.headerInteractive)
        assertTrue(policy.showDirectoryPreference)
    }
}
