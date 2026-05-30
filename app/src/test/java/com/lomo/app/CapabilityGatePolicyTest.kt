package com.lomo.app

/*
 * Behavior Contract:
 * - Unit under test: app-layer capability gate recovery policy catalog.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: expose one typed recovery contract for Android permissions and system capabilities.
 *
 * Scenarios:
 * - Given any declared capability id, when the shared catalog is queried, then exactly one policy is returned.
 * - Given runtime and user-selected capabilities, when sensitivity metadata is read, then sensitive
 *   data categories are typed independently from optional/blocking severity.
 * - Given system capabilities, when recovery metadata is read, then the primary system-settings action
 *   is explicit without blocking the first screen.
 * - Given Local Network, when recovery metadata is read, then its API-gated multi-permission runtime
 *   request, all-grants aggregation, app-settings fallback, and retry behavior are explicit.
 * - Given SAF-backed workspace and media capabilities, when recovery metadata is read, then user-selected
 *   recovery actions are explicit.
 *
 * Observable outcomes:
 * - Returned CapabilityGate and CapabilityRecoveryPlan values.
 *
 * TDD proof:
 * - RED: targeted app test fails before the fix because CapabilityGate has no typed sensitivity
 *   model or runtime action plan, and Local Network cannot expose an app-settings fallback.
 *
 * Excludes:
 * - Compose rendering, Android permission launchers, system Intent dispatch, localized copy.
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue

class CapabilityGatePolicyTest : AppFunSpec() {
    init {
        test("given any declared capability id when catalog is queried then exactly one policy is returned") {
            CapabilityGatePolicies.all.map { gate -> gate.id } shouldContainExactly CapabilityGateId.entries
            CapabilityGateId.entries.forEach { id ->
                CapabilityGatePolicies.requirePolicy(id).id shouldBe id
            }
        }

        test("given reminder system capabilities when recovery metadata is read then settings actions are explicit") {
            val notification = CapabilityGatePolicies.requirePolicy(CapabilityGateId.Notifications)
            val exactAlarm = CapabilityGatePolicies.requirePolicy(CapabilityGateId.ExactAlarm)

            assertSoftly(notification) {
                blockLevel shouldBe CapabilityBlockLevel.FeatureBlocked
                blocksFirstScreen shouldBe false
                sensitivityCategories shouldBe emptySet()
                recoveryPlan.primaryRequestAction shouldBe CapabilityRecoveryAction.RequestRuntimePermissions
                recoveryPlan.deniedFallbackAction shouldBe CapabilityRecoveryAction.OpenAppSettings
                recoveryPlan.permanentlyDeniedFallbackAction shouldBe CapabilityRecoveryAction.OpenAppSettings
                recoveryPlan.canRetryAfterRecovery shouldBe true
            }
            assertSoftly(exactAlarm) {
                blockLevel shouldBe CapabilityBlockLevel.FeatureBlocked
                blocksFirstScreen shouldBe false
                sensitivityCategories shouldBe emptySet()
                recoveryPlan.primaryRequestAction shouldBe
                    CapabilityRecoveryAction.OpenSettings(CapabilitySettingsEntry.ExactAlarmSettings)
                recoveryPlan.canRetryAfterRecovery shouldBe true
            }
        }

        test("given update install capability when recovery metadata is read then unknown-source recovery is explicit") {
            val install = CapabilityGatePolicies.requirePolicy(CapabilityGateId.InstallUnknownApps)

            assertSoftly(install) {
                blockLevel shouldBe CapabilityBlockLevel.FeatureBlocked
                blocksFirstScreen shouldBe false
                sensitivityCategories shouldBe emptySet()
                recoveryPlan.primaryRequestAction shouldBe
                    CapabilityRecoveryAction.OpenSettings(CapabilitySettingsEntry.UnknownAppSourcesSettings)
                recoveryPlan.canRetryAfterRecovery shouldBe true
            }
        }

        test("given sensitive optional enhancements when policies are read then sensitivity is separate from severity") {
            val expectations =
                mapOf(
                    CapabilityGateId.RecordAudio to setOf(CapabilitySensitivity.Microphone),
                    CapabilityGateId.Location to setOf(CapabilitySensitivity.Location),
                    CapabilityGateId.LocalNetwork to setOf(CapabilitySensitivity.NearbyDevicesOrLocalNetwork),
                )

            expectations.forEach { (id, sensitivity) ->
                withClue("capability=$id") {
                    val gate = CapabilityGatePolicies.requirePolicy(id)
                    gate.blockLevel shouldBe CapabilityBlockLevel.OptionalEnhancement
                    gate.blocksFirstScreen shouldBe false
                    gate.sensitivityCategories shouldBe sensitivity
                    gate.recoveryPlan.canRetryAfterRecovery shouldBe true
                }
            }
        }

        test("given Local Network when recovery metadata is read then API-gated multi-permission plan is explicit") {
            val localNetwork = CapabilityGatePolicies.requirePolicy(CapabilityGateId.LocalNetwork)
            val permissionPlan = requireNotNull(localNetwork.recoveryPlan.runtimePermissionPlan)

            assertSoftly(localNetwork) {
                blockLevel shouldBe CapabilityBlockLevel.OptionalEnhancement
                blocksFirstScreen shouldBe false
                sensitivityCategories shouldBe setOf(CapabilitySensitivity.NearbyDevicesOrLocalNetwork)
                recoveryPlan.primaryRequestAction shouldBe CapabilityRecoveryAction.RequestRuntimePermissions
                recoveryPlan.deniedFallbackAction shouldBe CapabilityRecoveryAction.OpenAppSettings
                recoveryPlan.permanentlyDeniedFallbackAction shouldBe CapabilityRecoveryAction.OpenAppSettings
                recoveryPlan.canRetryAfterRecovery shouldBe true
            }
            assertSoftly(permissionPlan) {
                grantAggregation shouldBe CapabilityGrantAggregation.All
                requiredPermissions(sdkInt = 32, isPermissionRecognized = { true }) shouldBe emptyList()
                requiredPermissions(sdkInt = 33, isPermissionRecognized = { true }) shouldBe
                    listOf(CapabilityPermissionNames.NearbyWifiDevices)
                requiredPermissions(sdkInt = 36, isPermissionRecognized = { true }) shouldBe
                    listOf(
                        CapabilityPermissionNames.NearbyWifiDevices,
                        CapabilityPermissionNames.AccessLocalNetwork,
                    )
                requiredPermissions(sdkInt = 36, isPermissionRecognized = { false }) shouldBe
                    listOf(CapabilityPermissionNames.NearbyWifiDevices)
            }
        }

        test("given SAF-backed capabilities when recovery metadata is read then data-safety impact is typed") {
            val workspace = CapabilityGatePolicies.requirePolicy(CapabilityGateId.SafWorkspaceAccess)
            val media = CapabilityGatePolicies.requirePolicy(CapabilityGateId.SafMediaAccess)

            assertSoftly(workspace) {
                blockLevel shouldBe CapabilityBlockLevel.FirstScreenBlocked
                blocksFirstScreen shouldBe true
                sensitivityCategories shouldBe setOf(CapabilitySensitivity.UserSelectedFiles)
                recoveryPlan.primaryRequestAction shouldBe CapabilityRecoveryAction.SelectSafTree
                recoveryPlan.canRetryAfterRecovery shouldBe true
            }
            assertSoftly(media) {
                blockLevel shouldBe CapabilityBlockLevel.FeatureBlocked
                blocksFirstScreen shouldBe false
                sensitivityCategories shouldBe setOf(CapabilitySensitivity.UserSelectedMedia)
                recoveryPlan.primaryRequestAction shouldBe CapabilityRecoveryAction.SelectSafDocument
                recoveryPlan.canRetryAfterRecovery shouldBe true
            }
        }

        test("given every policy when sensitivity is listed then typed categories cover the catalog") {
            CapabilityGatePolicies.all
                .flatMap { gate -> gate.sensitivityCategories }
                .distinct() shouldContainExactlyInAnyOrder CapabilitySensitivity.entries
        }
    }
}
