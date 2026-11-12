/*
 * Behavior Contract:
 * - Unit under test: MemoCurrentLocation policy helpers.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: derive coordinate provider selection, recency validation, and failure messages directly.
 *
 * Scenarios:
 * - Given permissions and enabled providers, candidate providers include fused, gps, and network as permitted.
 * - Given a platform without the public fused provider, an enabled "fused" name is not a candidate.
 * - Given location timestamps, evaluate fresh cache vs fallback window vs stale expiry.
 * - Given permission grant results, aggregation determines whether coordinate acquisition may proceed.
 * - Given failure states, messageResource maps each failure to its corresponding string resource.
 *
 * Observable outcomes:
 * - candidate provider lists, freshness and fallback booleans, permission grant decisions, resource ids.
 *
 * TDD proof:
 * - RED: fails before policy helpers exist; GREEN: passes when helpers enforce provider and cache boundaries.
 *
 * Excludes:
 * - Live OS positioning hardware and system permission dialogs.
 */

package com.lomo.app.feature.memo

import android.Manifest
import android.location.Location
import android.location.LocationManager
import com.lomo.app.R
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit

class MemoLocationPolicyTest : AppFunSpec() {
    init {
        test(
            "given enabled providers when fine permission is present then candidate list includes fused gps and network",
        ) {
            val enabled = setOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                "fused",
            )
            val candidates = resolveCandidateLocationProviders(
                enabledProviders = enabled,
                hasFinePermission = true,
                platformFusedProvider = "fused",
            )
            candidates shouldBe listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        }

        test("given no platform fused provider when a fused name is enabled then it is not a candidate") {
            val candidates = resolveCandidateLocationProviders(
                enabledProviders = setOf("fused", LocationManager.NETWORK_PROVIDER),
                hasFinePermission = true,
                platformFusedProvider = null,
            )
            candidates shouldBe listOf(LocationManager.NETWORK_PROVIDER)
        }

        test("given coarse permission only then gps is excluded from live providers") {
            val enabled = setOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )
            val candidates = resolveCandidateLocationProviders(
                enabledProviders = enabled,
                hasFinePermission = false,
                platformFusedProvider = null,
            )
            candidates shouldBe listOf(LocationManager.NETWORK_PROVIDER)
        }

        test("given location timestamps when evaluated then freshness and fallback windows are enforced") {
            val location = io.mockk.mockk<Location>()
            io.mockk.every { location.elapsedRealtimeNanos } returns 1_000_000_000L
            io.mockk.every { location.time } returns 0L

            // Exactly 30 seconds after fix
            val freshNanos = 1_000_000_000L + TimeUnit.SECONDS.toNanos(30)
            isLocationFresh(location, nowElapsedNanos = freshNanos) shouldBe true
            isLocationWithinFallbackMaxAge(location, nowElapsedNanos = freshNanos) shouldBe true

            // 10 minutes after fix: not fresh, but within fallback window (<= 2 hours)
            val fallbackNanos = 1_000_000_000L + TimeUnit.MINUTES.toNanos(10)
            isLocationFresh(location, nowElapsedNanos = fallbackNanos) shouldBe false
            isLocationWithinFallbackMaxAge(location, nowElapsedNanos = fallbackNanos) shouldBe true

            // 3 hours after fix: exceeds fallback window
            val expiredNanos = 1_000_000_000L + TimeUnit.HOURS.toNanos(3)
            isLocationFresh(location, nowElapsedNanos = expiredNanos) shouldBe false
            isLocationWithinFallbackMaxAge(location, nowElapsedNanos = expiredNanos) shouldBe false
        }

        test("given permission callback results then coordinate permission grant is decided") {
            isCoordinatePermissionGranted(
                permissionResults = mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true),
                hasCurrentPermission = false,
            ) shouldBe true

            isCoordinatePermissionGranted(
                permissionResults = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true),
                hasCurrentPermission = false,
            ) shouldBe true

            isCoordinatePermissionGranted(
                permissionResults = mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to false,
                ),
                hasCurrentPermission = false,
            ) shouldBe false

            isCoordinatePermissionGranted(
                permissionResults = emptyMap<String, Boolean>(),
                hasCurrentPermission = true,
            ) shouldBe true
        }

        test("given failure reasons then message resources map correctly") {
            MemoLocationFailure.PermissionDenied.messageResource() shouldBe R.string.memo_coordinates_permission_denied
            MemoLocationFailure.Unavailable.messageResource() shouldBe R.string.memo_coordinates_unavailable
            MemoLocationFailure.TimedOut.messageResource() shouldBe R.string.memo_coordinates_timeout
            MemoLocationFailure.InvalidFix.messageResource() shouldBe R.string.memo_coordinates_invalid_fix
        }
    }
}
