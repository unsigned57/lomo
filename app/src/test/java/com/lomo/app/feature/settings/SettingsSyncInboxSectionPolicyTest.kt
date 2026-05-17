package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

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
class SettingsSyncInboxSectionPolicyTest : AppFunSpec() {
    init {
        test("collapsed section stays interactive when sync inbox is disabled") {
            val policy = SyncInboxSectionPolicies.resolve(enabled = false)

            ((policy.showSectionHeader)) shouldBe true
            ((policy.headerInteractive)) shouldBe true
            ((policy.showDirectoryPreference)) shouldBe false
        }
    }

    init {
        test("expanded section shows directory preference when sync inbox is enabled") {
            val policy = SyncInboxSectionPolicies.resolve(enabled = true)

            ((policy.showSectionHeader)) shouldBe true
            ((policy.headerInteractive)) shouldBe true
            ((policy.showDirectoryPreference)) shouldBe true
        }
    }

}
