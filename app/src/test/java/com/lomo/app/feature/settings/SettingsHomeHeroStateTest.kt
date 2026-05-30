package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SettingsHomeHero state derivation (computeSettingsHomeHeroState).
 * - Behavior focus: derive which Hero card to show on the redesigned Settings root from
 *   the per-provider enabled flag, the last successful sync timestamp, and the active
 *   UnifiedSyncState of each cloud provider (Git / WebDAV / S3).
 * - Given/When/Then scenarios:
 *   1. Given no provider is enabled, when the Hero is composed, then state is NotConfigured.
 *   2. Given two providers are enabled with different lastSync timestamps, when the Hero is
 *      composed, then the Active state reports both providers in declaration order and the
 *      most recent timestamp.
 *   3. Given any enabled provider is currently Running, when the Hero is composed, then
 *      isCurrentlySyncing is true.
 *   4. Given an enabled provider has lastSync = 0L (never synced) and no other provider has
 *      synced either, then lastSuccessfulSyncMillis is null.
 * - Observable outcomes: SettingsHomeHeroState data class returned by the pure function.
 * - Red phase: fails at compile time because computeSettingsHomeHeroState / SettingsHomeHeroState
 *   do not yet exist; the Hero component has not been built.
 * - Excludes: Compose rendering, relative-time formatting, sync trigger wiring, Hero visuals.
 */
class SettingsHomeHeroStateTest : AppFunSpec() {
    init {
        test("NotConfigured when no provider is enabled") {
            val state =
                computeSettingsHomeHeroState(
                    gitEnabled = false,
                    gitLastSync = 0L,
                    gitSyncState = UnifiedSyncState.Idle,
                    webDavEnabled = false,
                    webDavLastSync = 0L,
                    webDavSyncState = UnifiedSyncState.Idle,
                    s3Enabled = false,
                    s3LastSync = 0L,
                    s3SyncState = UnifiedSyncState.Idle,
                )

            state shouldBe SettingsHomeHeroState.NotConfigured
        }

        test("Active aggregates enabled providers and picks the most recent timestamp") {
            val state =
                computeSettingsHomeHeroState(
                    gitEnabled = true,
                    gitLastSync = 1_700_000_000_000L,
                    gitSyncState = UnifiedSyncState.Idle,
                    webDavEnabled = false,
                    webDavLastSync = 0L,
                    webDavSyncState = UnifiedSyncState.Idle,
                    s3Enabled = true,
                    s3LastSync = 1_700_000_500_000L,
                    s3SyncState = UnifiedSyncState.Idle,
                ) as SettingsHomeHeroState.Active

            state.activeProviders shouldBe listOf(SyncBackendType.GIT, SyncBackendType.S3)
            state.lastSuccessfulSyncMillis shouldBe 1_700_000_500_000L
            state.isCurrentlySyncing shouldBe false
        }

        test("Active reports isCurrentlySyncing when any enabled provider is running") {
            val state =
                computeSettingsHomeHeroState(
                    gitEnabled = true,
                    gitLastSync = 1_700_000_000_000L,
                    gitSyncState =
                        UnifiedSyncState.Running(SyncBackendType.GIT, UnifiedSyncPhase.PULLING),
                    webDavEnabled = false,
                    webDavLastSync = 0L,
                    webDavSyncState = UnifiedSyncState.Idle,
                    s3Enabled = false,
                    s3LastSync = 0L,
                    s3SyncState = UnifiedSyncState.Idle,
                ) as SettingsHomeHeroState.Active

            state.isCurrentlySyncing shouldBe true
        }

        test("lastSuccessfulSyncMillis is null when no provider has yet completed a sync") {
            val state =
                computeSettingsHomeHeroState(
                    gitEnabled = true,
                    gitLastSync = 0L,
                    gitSyncState = UnifiedSyncState.Idle,
                    webDavEnabled = false,
                    webDavLastSync = 0L,
                    webDavSyncState = UnifiedSyncState.Idle,
                    s3Enabled = false,
                    s3LastSync = 0L,
                    s3SyncState = UnifiedSyncState.Idle,
                ) as SettingsHomeHeroState.Active

            state.lastSuccessfulSyncMillis shouldBe null
        }
    }
}
