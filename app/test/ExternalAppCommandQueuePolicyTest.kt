/*
 * Behavior Contract:
 * - Unit under test: ExternalAppCommandQueuePolicy
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: maintain a short-lived durable queue for external launcher, widget, and quick
 *   settings commands without dropping commands before they reach a terminal state.
 *
 * Scenarios:
 * - Given a new external command, when it is enqueued before expiry, then it remains pending.
 * - Given an equivalent pending command already exists, when a newer command is enqueued, then the
 *   queue keeps one command for that source/action and resets it to Pending.
 * - Given a command is waiting on a prerequisite, when the prerequisite state changes, then the
 *   command status is updated without removing the command.
 * - Given commands pass their TTL, when expiry is applied, then expired commands are removed and
 *   reported.
 * - Given a command reaches a terminal state, when completion is applied, then the command is
 *   removed from the pending queue.
 *
 * Observable outcomes: queue contents, command IDs, status, and expired command IDs.
 *
 * TDD proof:
 * - RED command: ./kotlin test --include-classes='com.lomo.app.ExternalAppCommandQueuePolicyTest'
 * - RED symptom: ExternalAppCommandQueuePolicy and ExternalAppCommand do not exist.
 *
 * Excludes: Android SharedPreferences I/O, Activity lifecycle, launcher rendering, and Compose
 * execution effects.
 */
package com.lomo.app

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ExternalAppCommandQueuePolicyTest : AppFunSpec() {
    init {
        test("given new command when enqueued before expiry then it remains pending") {
            val command = command(id = "cmd-1")

            val result =
                ExternalAppCommandQueuePolicy.enqueue(
                    commands = emptyList(),
                    command = command,
                    nowMillis = 1_000L,
                )

            result.commands shouldBe listOf(command)
            result.enqueuedCommand shouldBe command
        }

        test("given equivalent pending command when newer command enqueued then one pending command remains") {
            val older =
                command(
                    id = "cmd-old",
                    status = ExternalAppCommandStatus.WaitingForVoiceDirectory,
                    createdAtMillis = 1_000L,
                )
            val newer =
                command(
                    id = "cmd-new",
                    createdAtMillis = 2_000L,
                )

            val result =
                ExternalAppCommandQueuePolicy.enqueue(
                    commands = listOf(older),
                    command = newer,
                    nowMillis = 2_000L,
                )

            result.commands shouldBe listOf(newer)
            result.enqueuedCommand shouldBe newer
        }

        test("given waiting command when status updated then command remains queued") {
            val queued = command(id = "cmd-1")

            val updated =
                ExternalAppCommandQueuePolicy.updateStatus(
                    commands = listOf(queued),
                    commandId = "cmd-1",
                    status = ExternalAppCommandStatus.WaitingForRecordAudioPermission,
                )

            updated shouldBe
                listOf(
                    queued.copy(status = ExternalAppCommandStatus.WaitingForRecordAudioPermission),
                )
        }

        test("given expired commands when expiry applied then expired commands are removed and reported") {
            val expired = command(id = "expired", expiresAtMillis = 1_500L)
            val active = command(id = "active", expiresAtMillis = 3_000L)

            val result =
                ExternalAppCommandQueuePolicy.expire(
                    commands = listOf(expired, active),
                    nowMillis = 2_000L,
                )

            result.commands shouldBe listOf(active)
            result.expiredCommandIds.shouldContainExactly("expired")
        }

        test("given terminal command when completed then command is removed") {
            val queued = command(id = "cmd-1")

            ExternalAppCommandQueuePolicy.complete(
                commands = listOf(queued),
                commandId = "cmd-1",
            ) shouldBe emptyList()
        }
    }
}

private fun command(
    id: String = "cmd",
    action: ExternalAppCommandAction = ExternalAppCommandAction.StartRecording,
    source: ExternalAppCommandSource = ExternalAppCommandSource.QuickSettingsTile,
    status: ExternalAppCommandStatus = ExternalAppCommandStatus.Pending,
    createdAtMillis: Long = 1_000L,
    expiresAtMillis: Long = createdAtMillis + EXTERNAL_APP_COMMAND_TTL_MILLIS,
): ExternalAppCommand =
    ExternalAppCommand(
        id = id,
        action = action,
        source = source,
        status = status,
        createdAtMillis = createdAtMillis,
        expiresAtMillis = expiresAtMillis,
    )
