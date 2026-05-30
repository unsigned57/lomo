package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncProviderModels remote-absence verification mapping.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: convert provider-specific S3 remote verification evidence into the provider-neutral RemoteSync planner
 *   contract before planning.
 *
 * Scenarios:
 * - Given S3 has verified the remote object is absent, when converting to planner input, then absence is verified.
 * - Given S3 evidence comes from cached, suspect, or unknown remote state, when converting to planner input, then
 *   absence is unverified.
 *
 * Observable outcomes:
 * - RemoteSyncRemoteAbsenceVerification values returned by the S3 provider mapping helpers.
 *
 * TDD proof:
 * - Target command: ./gradlew --no-daemon --no-configuration-cache --console=plain
 *   :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3SyncProviderModelsTest'
 * - Observed RED: not separately observed for this file; the RED run was captured in
 *   S3SyncPlannerVerificationLevelTest after switching planner inputs to RemoteSyncRemoteAbsenceVerification.
 * - Why this still proves the behavior: that compile failure showed the neutral planner type was missing. This test
 *   locks the S3 adapter mapping added to satisfy that contract so callers no longer pass provider-specific types
 *   into planning.
 *
 * Excludes:
 * - S3 transport, remote index loading, executor re-planning, and action application.
 */
class S3SyncProviderModelsTest : DataFunSpec() {
    init {
        test("given verified S3 absence when mapped to planner verification then absence is verified") {
            S3RemoteVerificationLevel.VERIFIED_REMOTE
                .toRemoteSyncRemoteAbsenceVerification() shouldBe
                RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT
        }

        test("given non-authoritative S3 absence evidence when mapped to planner verification then absence is unverified") {
            val mapped =
                listOf(
                    S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                    S3RemoteVerificationLevel.SUSPECT_REMOTE_MISSING,
                    S3RemoteVerificationLevel.UNKNOWN_REMOTE,
                ).map(S3RemoteVerificationLevel::toRemoteSyncRemoteAbsenceVerification)

            mapped shouldBe
                listOf(
                    RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
                    RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
                    RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
                )
        }

        test("given S3 absence map when mapped to planner verification then every path uses neutral evidence") {
            val mapped =
                mapOf(
                    "verified.md" to S3RemoteVerificationLevel.VERIFIED_REMOTE,
                    "cached.md" to S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                ).toS3RemoteSyncRemoteAbsenceVerifications()

            mapped shouldBe
                mapOf(
                    "verified.md" to RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT,
                    "cached.md" to RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT,
                )
        }
    }
}
