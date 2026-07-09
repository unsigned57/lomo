package com.lomo.app

import android.content.Intent
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.RecordingDeepLink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
 * - Given external command Intents, when calling extractPendingLaunchActions, then it extracts nothing because external commands use the durable command queue.
 * - Given a restored Activity instance is launched from task history, when initial launch actions are extracted, then old commands are not replayed.
 * - Given an Intent with RecordingDeepLink.ACTION_OPEN_SAVED_MEMO and a memo ID extra, when calling extractPendingLaunchActions, then it extracts PendingLaunchAction.OpenMemo(memoId).
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
 * - RED command: ./kotlin test --include-classes='com.lomo.app.MainActivityLaunchRoutingTest'
 * - RED symptom: tests fail because launch routing has no trusted verifier seam for stop-recording and still exposes OpenRecordingEntry as a separate command.
 * - RED command: ./kotlin test --include-classes='com.lomo.app.MainActivityLaunchRoutingTest'
 * - RED symptom: tests fail because restored Activity initial launch extraction does not exist, and onCreate gates all restored launches behind savedInstanceState == null.
 * - RED command: ./kotlin test --include-classes='com.lomo.app.MainActivityLaunchRoutingTest'
 * - RED symptom: tests fail because durable external commands are still modeled as transient PendingLaunchAction values.
 * - GREEN command: ./kotlin test --include-classes='com.lomo.app.MainActivityLaunchRoutingTest'
 * - GREEN symptom: BUILD SUCCESSFUL with external command actions excluded from transient launch routing.
 *
 * Excludes:
 * - Actual execution of launch actions on the navigation stack, Compose rendering, biometric prompt wiring, and persistence of transient payloads across process death.
 *
 * Test Change Justification:
 * - Reason category: entry contract extension.
 * - Old behavior/assertion being replaced: launch routing only covered memo/share/reminder actions.
 * - Why old assertion is no longer correct: recording shortcuts and saved-recording notifications are now supported entry points.
 * - Coverage preserved by: existing memo/share/reminder routing scenarios remain, with recording scenarios added.
 * - Why this is not fitting the test to the implementation: assertions verify public launch actions and capability gates.
 */
class MainActivityLaunchRoutingTest : AppFunSpec() {
    init {
        test("extractPendingLaunchActions parses reminder action and returns OpenMemo action") {
            val intent =
                TestIntent(
                    actionValue = "com.lomo.reminder.action.OPEN",
                    stringExtras = mapOf("memo_id" to "memo-123"),
                )

            val actions = extractPendingLaunchActions(intent)

            actions shouldBe listOf(PendingLaunchAction.OpenMemo("memo-123"))
        }

        test("extractPendingLaunchActions parses reminder action with missing memo ID and returns empty list") {
            val intent = TestIntent(actionValue = "com.lomo.reminder.action.OPEN")

            val actions = extractPendingLaunchActions(intent)

            actions shouldBe emptyList()
        }

        test("extractPendingLaunchActions ignores external command actions") {
            listOf(
                "com.lomo.app.ACTION_NEW_MEMO",
                "com.lomo.app.ACTION_START_RECORDING",
                "com.lomo.app.ACTION_STOP_RECORDING",
                MainActivity.ACTION_EXTERNAL_APP_COMMAND,
            ).forEach { action ->
                val actions = extractPendingLaunchActions(TestIntent(actionValue = action))

                actions shouldBe emptyList()
            }
        }

        test("extractInitialPendingLaunchActions skips replay when restored from task history") {
            val historyIntent =
                TestIntent("com.lomo.app.ACTION_START_RECORDING")
                    .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

            val actions =
                extractInitialPendingLaunchActions(
                    activityInstanceState = ActivityInstanceState.Restored,
                    intent = historyIntent,
                )

            actions shouldBe emptyList()
        }

        test("extractPendingLaunchActions parses recording saved memo action") {
            val intent =
                TestIntent(
                    actionValue = RecordingDeepLink.ACTION_OPEN_SAVED_MEMO,
                    stringExtras = mapOf(RecordingDeepLink.EXTRA_MEMO_ID to "memo-recording"),
                )

            val actions = extractPendingLaunchActions(intent)

            actions shouldBe listOf(PendingLaunchAction.OpenMemo("memo-recording"))
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

private class TestIntent(
    private val actionValue: String,
    private val stringExtras: Map<String, String> = emptyMap(),
) : Intent() {
    private val mutableStringExtras = stringExtras.toMutableMap()
    private var mutableFlags: Int = 0

    override fun getAction(): String = actionValue

    override fun getFlags(): Int = mutableFlags

    override fun addFlags(flags: Int): Intent {
        mutableFlags = mutableFlags or flags
        return this
    }

    override fun getStringExtra(name: String): String? = mutableStringExtras[name]

    override fun putExtra(
        name: String,
        value: String?,
    ): Intent {
        if (value == null) {
            mutableStringExtras.remove(name)
        } else {
            mutableStringExtras[name] = value
        }
        return this
    }
}
