package com.lomo.app

import android.content.Intent
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/*
 * Behavior Contract:
 * - Unit under test: extractPendingLaunchActions, resolveEntryFlowState, and
 *   resolvePendingLaunchCommandEntryFlowState
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: classify Android, Widget, reminder, and default launch entries against app-lock
 *   and workspace readiness before Activity/UI dispatches them.
 *
 * Scenarios:
 * - Given an Intent with action "com.lomo.reminder.action.OPEN" and a memo ID extra, when calling extractPendingLaunchActions, then it extracts PendingLaunchAction.OpenMemo(memoId).
 * - Given an Intent with action "com.lomo.reminder.action.OPEN" and no memo ID extra, when calling extractPendingLaunchActions, then it extracts nothing.
 * - Given an external pending command while app lock is resolving, when resolving entry flow, then the state waits and preserves the queued command identity.
 * - Given an external pending command while app lock is active, when resolving entry flow, then the state is blocked and preserves the queued command identity.
 * - Given a configured workspace that is still preparing, when resolving an external pending command, then the state waits without dropping the queued command identity.
 * - Given a Widget/open/share pending command while the root workspace is missing, when resolving entry flow, then the state is recoverable with the queued command identity.
 * - Given a shared image pending command while image workspace capability is missing, when resolving entry flow, then the state is recoverable and names the missing image capability without dropping command identity.
 * - Given pending commands with all required readiness present, when resolving entry flow, then the state exposes the ready command destination without losing payload data or command identity.
 * - Given a normal launch with no pending action, when readiness is complete, then the state is ready for the default app surface.
 * - Given routing API callers have an action but no queued command, when they inspect supported entry points, then no raw-action resolver exists that can synthesize a fake command identity.
 *
 * Observable outcomes:
 * - List of PendingLaunchAction.
 * - EntryFlowState subtype, retained EntryFlowRequest.PendingCommand/PendingLaunchCommand,
 *   missing capability set, and ready PendingLaunchCommand payload.
 * - Public-in-module resolver method shape available to app callers.
 *
 * TDD proof:
 * - RED command: KOTEST_INCLUDE_PATTERN='com.lomo.app.MainActivityLaunchRoutingTest' ./gradlew --no-daemon --no-configuration-cache --console=plain :app:testDebugUnitTest
 * - RED symptom: MainActivityLaunchRoutingTest > entry flow API exposes no raw action resolver that can synthesize a fake command identity FAILED because the raw PendingLaunchAction resolver overload was still declared.
 * - GREEN command: KOTEST_INCLUDE_PATTERN='com.lomo.app.MainActivityLaunchRoutingTest' ./gradlew --no-daemon --no-configuration-cache --console=plain :app:testDebugUnitTest -x :data:kspDebugKotlin -x :data:compileDebugKotlin -x :data:compileDebugJavaWithJavac -x :data:processDebugJavaRes -x :data:transformDebugClassesWithAsm -x :data:bundleLibCompileToJarDebug -x :data:bundleLibRuntimeToJarDebug
 * - GREEN symptom: BUILD SUCCESSFUL with MainActivityLaunchRoutingTest passing. The unexcluded command was blocked before app tests by unrelated parallel data compile error: data/src/main/java/com/lomo/data/git/GitSyncConflictRecoveryCoordinator.kt:194:56 Unresolved reference 'file'.
 *
 * Excludes:
 * - Actual execution of launch actions on the navigation stack, Compose rendering, biometric prompt wiring, and persistence of transient payloads across process death.
 */
class MainActivityLaunchRoutingTest : AppFunSpec() {
    init {
        test("extractPendingLaunchActions parses reminder action and returns OpenMemo action") {
            val intent = mockk<Intent>()
            every { intent.action } returns "com.lomo.reminder.action.OPEN"
            every { intent.getStringExtra("memo_id") } returns "memo-123"

            val actions = extractPendingLaunchActions(intent)

            actions shouldBe listOf(PendingLaunchAction.OpenMemo("memo-123"))
        }

        test("extractPendingLaunchActions parses reminder action with missing memo ID and returns empty list") {
            val intent = mockk<Intent>()
            every { intent.action } returns "com.lomo.reminder.action.OPEN"
            every { intent.getStringExtra("memo_id") } returns null

            val actions = extractPendingLaunchActions(intent)

            actions shouldBe emptyList()
        }

        test("entry flow waits for app lock resolution while preserving pending command identity") {
            val command = PendingLaunchCommand(
                id = 41L,
                action = PendingLaunchAction.SharedText("from share sheet"),
            )

            val state =
                resolvePendingLaunchCommandEntryFlowState(
                    command = command,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Resolving,
                            configuredCapabilities =
                                setOf(
                                    EntryCapability.RootWorkspace,
                                    EntryCapability.ImageWorkspace,
                                ),
                        ),
                )

            state shouldBe EntryFlowState.WaitingForAppLockResolution(
                request = EntryFlowRequest.PendingCommand(command),
            )
        }

        test("entry flow blocks locked pending command while preserving command identity") {
            val command = PendingLaunchCommand(
                id = 42L,
                action = PendingLaunchAction.SharedText("from share sheet"),
            )

            val state =
                resolvePendingLaunchCommandEntryFlowState(
                    command = command,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Locked,
                            configuredCapabilities =
                                setOf(
                                    EntryCapability.RootWorkspace,
                                    EntryCapability.ImageWorkspace,
                                ),
                        ),
                )

            state shouldBe EntryFlowState.BlockedByAppLock(
                request = EntryFlowRequest.PendingCommand(command),
            )
        }

        test("entry flow keeps default launch blocked without synthesizing a pending command") {
            val state =
                resolveEntryFlowState(
                    request = EntryFlowRequest.DefaultLaunch,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Locked,
                            configuredCapabilities = setOf(EntryCapability.RootWorkspace),
                        ),
                )

            state shouldBe EntryFlowState.BlockedByAppLock(request = EntryFlowRequest.DefaultLaunch)
        }

        test("entry flow blocks locked external command while preserving original shared text") {
            val action = PendingLaunchAction.SharedText("from share sheet")
            val command = PendingLaunchCommand(id = 49L, action = action)

            val state =
                resolveEntryFlowState(
                    request = EntryFlowRequest.PendingCommand(command),
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Locked,
                            configuredCapabilities =
                                setOf(
                                    EntryCapability.RootWorkspace,
                                    EntryCapability.ImageWorkspace,
                                ),
                        ),
                )

            state shouldBe EntryFlowState.BlockedByAppLock(
                request = EntryFlowRequest.PendingCommand(command),
            )
        }

        test("entry flow waits for configured workspace readiness while preserving pending command identity") {
            val command = PendingLaunchCommand(
                id = 43L,
                action = PendingLaunchAction.OpenMemo("memo-from-reminder"),
            )

            val state =
                resolvePendingLaunchCommandEntryFlowState(
                    command = command,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Unlocked,
                            workspace = EntryWorkspaceState.Preparing,
                            configuredCapabilities = setOf(EntryCapability.RootWorkspace),
                        ),
                )

            state shouldBe EntryFlowState.WaitingForWorkspaceReadiness(
                request = EntryFlowRequest.PendingCommand(command),
            )
        }

        test("entry flow keeps widget open command recoverable when root workspace is not configured") {
            val command = PendingLaunchCommand(
                id = 44L,
                action = PendingLaunchAction.OpenMemo("memo-widget"),
            )

            val state =
                resolvePendingLaunchCommandEntryFlowState(
                    command = command,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Unlocked,
                            configuredCapabilities = emptySet(),
                        ),
                )

            state shouldBe
                EntryFlowState.NeedsWorkspaceSetup(
                    request = EntryFlowRequest.PendingCommand(command),
                    missingCapabilities = setOf(EntryCapability.RootWorkspace),
                )
        }

        test("entry flow keeps share image command recoverable when image workspace is missing") {
            val command = PendingLaunchCommand(
                id = 45L,
                action = PendingLaunchAction.SharedImage(mockk()),
            )

            val state =
                resolvePendingLaunchCommandEntryFlowState(
                    command = command,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Unlocked,
                            configuredCapabilities = setOf(EntryCapability.RootWorkspace),
                        ),
                )

            state shouldBe
                EntryFlowState.MissingRequiredCapability(
                    request = EntryFlowRequest.PendingCommand(command),
                    missingCapabilities = setOf(EntryCapability.ImageWorkspace),
                )
        }

        test("entry flow maps ready pending commands without dropping payloads or command identity") {
            val sharedText = PendingLaunchCommand(
                id = 46L,
                action = PendingLaunchAction.SharedText("note body"),
            )
            val openMemo = PendingLaunchCommand(
                id = 47L,
                action = PendingLaunchAction.OpenMemo("memo-42"),
            )
            val readiness =
                EntryFlowReadiness(
                    appLock = EntryAppLockState.Unlocked,
                    configuredCapabilities =
                        setOf(
                            EntryCapability.RootWorkspace,
                            EntryCapability.ImageWorkspace,
                        ),
                )

            resolvePendingLaunchCommandEntryFlowState(
                command = sharedText,
                readiness = readiness,
            ) shouldBe EntryFlowState.Ready(sharedText)

            resolvePendingLaunchCommandEntryFlowState(
                command = openMemo,
                readiness = readiness,
            ) shouldBe EntryFlowState.Ready(openMemo)
        }

        test("entry flow resolves normal launch as ready default surface when readiness is complete") {
            val state =
                resolveEntryFlowState(
                    request = EntryFlowRequest.DefaultLaunch,
                    readiness =
                        EntryFlowReadiness(
                            appLock = EntryAppLockState.Unlocked,
                            configuredCapabilities = setOf(EntryCapability.RootWorkspace),
                        ),
                )

            state shouldBe EntryFlowState.ReadyForDefaultLaunch
        }

        test("entry flow API exposes no raw action resolver that can synthesize a fake command identity") {
            val rawActionResolverMethods =
                Class.forName("com.lomo.app.MainActivityLaunchRoutingKt")
                    .declaredMethods
                    .filter { method ->
                        method.name == "resolveEntryFlowState" &&
                            method.parameterTypes.any { parameterType ->
                                parameterType == PendingLaunchAction::class.java
                            }
                    }

            rawActionResolverMethods.shouldBeEmpty()
        }
    }
}
