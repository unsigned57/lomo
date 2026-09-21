package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SettingsHomeHero state derivation (computeSettingsHomeHeroState).
 * - Capability: derive the Settings home Hero card from per-provider enabled flag, last sync
 *   timestamp, and active UnifiedSyncState.
 * - Behavior focus: derive which Hero card to show on the redesigned Settings root from
 *   the per-provider enabled flag, the last successful sync timestamp, and the active
 *   UnifiedSyncState of each cloud provider (Git / WebDAV / S3).
 * Scenarios:
 *   1. Given no provider is enabled, when the Hero is composed, then state is NotConfigured.
 *   2. Given two providers are enabled with different lastSync timestamps, when the Hero is
 *      composed, then the Active state reports both providers in declaration order and the
 *      most recent timestamp.
 *   3. Given any enabled provider is currently Running, when the Hero is composed, then
 *      isCurrentlySyncing is true.
 *   4. Given an enabled provider has lastSync = 0L (never synced) and no other provider has
 *      synced either, then lastSuccessfulSyncMillis is null.
 * - Observable outcomes: SettingsHomeHeroState data class returned by the pure function.
 * - TDD proof: fails at compile time because computeSettingsHomeHeroState / SettingsHomeHeroState
 *   do not yet exist; the Hero component has not been built.
 * - Excludes: Compose rendering, relative-time formatting, sync trigger wiring, Hero visuals.
 * Test Change Justification:
 * - Reason category: production signature refactor (parameter object).
 * - Old behavior/assertion being replaced: per-provider enabled/lastSync/syncState triples as
 *   flat parameters.
 * - Why old assertion is no longer correct: provider facts collapsed into HomeHeroProviderFacts;
 *   the same hero rendering decisions are still asserted.
 * - Coverage preserved by: identical enabled/disabled/running-state scenarios.
 * - Why this is not fitting the test to the implementation: rendered hero state is asserted the
 *   same way; only input grouping changed.
 */
class SettingsHomeHeroStateTest : AppFunSpec() {
    init {
        test("NotConfigured when no provider is enabled") {
            val state =
                computeSettingsHomeHeroState(
                    git = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                    webDav = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                    s3 = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                )

            state shouldBe SettingsHomeHeroState.NotConfigured
        }

        test("Active aggregates enabled providers and picks the most recent timestamp") {
            val state =
                computeSettingsHomeHeroState(
                    git = HomeHeroProviderFacts(true, 1_700_000_000_000L, UnifiedSyncState.Idle),
                    webDav = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                    s3 = HomeHeroProviderFacts(true, 1_700_000_500_000L, UnifiedSyncState.Idle),
                ) as SettingsHomeHeroState.Active

            state.activeProviders shouldBe listOf(SyncBackendType.GIT, SyncBackendType.S3)
            state.lastSuccessfulSyncMillis shouldBe 1_700_000_500_000L
            state.isCurrentlySyncing shouldBe false
        }

        test("Active reports isCurrentlySyncing when any enabled provider is running") {
            val state =
                computeSettingsHomeHeroState(
                    git =
                        HomeHeroProviderFacts(
                            true,
                            1_700_000_000_000L,
                            UnifiedSyncState.Running(SyncBackendType.GIT, UnifiedSyncPhase.PULLING),
                        ),
                    webDav = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                    s3 = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                ) as SettingsHomeHeroState.Active

            state.isCurrentlySyncing shouldBe true
        }

        test("lastSuccessfulSyncMillis is null when no provider has yet completed a sync") {
            val state =
                computeSettingsHomeHeroState(
                    git = HomeHeroProviderFacts(true, 0L, UnifiedSyncState.Idle),
                    webDav = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                    s3 = HomeHeroProviderFacts(false, 0L, UnifiedSyncState.Idle),
                ) as SettingsHomeHeroState.Active

            state.lastSuccessfulSyncMillis shouldBe null
        }
    }
}
