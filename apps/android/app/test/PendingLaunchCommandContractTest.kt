// adversarial-audit: hypotheses under test:
// 1. A Restored activity whose intent lacks FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY must not
//    re-extract pending launch actions: PendingLaunchCommandQueue's saved-state snapshot is the
//    sole replay channel, and re-extraction double-fires commands (e.g. a config-change recreate
//    for fontScale — not declared in the manifest's configChanges — re-imports shared text).
// 2. Restored queue ids must never collide with post-restore enqueues.
package com.lomo.app

import android.content.Intent
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: pending launch command extraction/restoration boundary.
 * - Owning layer: app.
 * - Priority tier: P0.
 * - Capability: the saved-state queue is the sole replay channel for restored activities;
 *   re-extraction can only duplicate commands, restored ids never collide with new enqueues,
 *   and restore merges rather than drops earlier commands.
 *
 * Scenarios:
 * - Given a restored activity without the task-history flag, when its intent is offered, then it
 *   is not re-extracted.
 * - Given a trusted launch intent whose original completed, when re-delivered, then the store
 *   re-accepts it.
 * - Given a restored queue, when new commands enqueue, then their ids never collide with
 *   restored ids.
 * - Given commands enqueued before restore, when the queue restores, then it merges instead of
 *   silently dropping them.
 *
 * Observable outcomes: extracted/queued command sets, id uniqueness, merge results.
 *
 * TDD proof:
 * - The no-re-extract and merge arms fail RED while re-extraction or replace-on-restore ran.
 *
 * Excludes:
 * - The commands' downstream effects and intent-flag plumbing outside the queue contract.
 */
class PendingLaunchCommandContractTest : AppFunSpec() {
    init {
        test("a restored activity without the task-history flag must not re-extract its initial intent") {
            // The saved-state queue already carries every undispatched command and dispatched
            // commands were consumed; re-extracting the same intent can only duplicate work.
            // Config-change recreation (fontScale is not in configChanges) reaches exactly this
            // path — Restored + original intent, no history flag.
            val recreatedIntent =
                ProbeIntent(
                    actionValue = "com.lomo.reminder.action.OPEN",
                    stringExtras = mapOf("memo_id" to "memo-123"),
                )

            val actions =
                extractInitialPendingLaunchActions(
                    activityInstanceState = ActivityInstanceState.Restored,
                    intent = recreatedIntent,
                )

            actions shouldBe emptyList()
        }

        test("a trusted launch intent is re-accepted by the store after the original completed") {
            // On the same recreate path the trusted command is re-extracted and re-enqueued:
            // dedupeKey only dedupes among still-queued commands, so a completed command is
            // accepted again within its 10-minute TTL.
            val command =
                ExternalAppCommand(
                    id = "cmd-1",
                    action = ExternalAppCommandAction.CreateMemo,
                    source = ExternalAppCommandSource.Widget,
                    status = ExternalAppCommandStatus.Pending,
                    createdAtMillis = 1_000L,
                    expiresAtMillis = 1_000L + EXTERNAL_APP_COMMAND_TTL_MILLIS,
                    payload = null,
                )
            val first =
                ExternalAppCommandQueuePolicy.enqueue(
                    commands = emptyList(),
                    command = command,
                    nowMillis = 2_000L,
                )
            val completed =
                ExternalAppCommandQueuePolicy.complete(
                    commands = first.commands,
                    commandId = command.id,
                )
            val reEnqueued =
                ExternalAppCommandQueuePolicy.enqueue(
                    commands = completed,
                    command = command,
                    nowMillis = 3_000L,
                )

            // Desired: a completed command id stays terminal — re-delivery must not resurrect it.
            reEnqueued.enqueuedCommand shouldBe null
        }

        test("post-restore enqueues never reuse restored command ids") {
            val queue = PendingLaunchCommandQueue()
            queue.enqueue(PendingLaunchAction.SharedText("first"))
            val saved = queue.snapshotJson()

            val restored = PendingLaunchCommandQueue()
            restored.restore(saved)
            restored.enqueue(PendingLaunchAction.SharedText("second"))

            val ids = restored.commands.map { it.id }
            ids.size shouldBe ids.distinct().size
            restored.commands.last().id shouldNotBe 0L
        }

        test("restoring a queue must merge rather than silently drop commands enqueued earlier") {
            val queue = PendingLaunchCommandQueue()
            queue.enqueue(PendingLaunchAction.OpenMemo("already-queued"))
            queue.restore(
                """{"nextCommandId":7,"commands":[{"id":3,"action":"open_memo","payload":"restored"}]}""",
            )

            // Desired: restore preserves the in-memory tail; actual: restore() replaces the
            // whole list and drops "already-queued" without any observable rejection.
            queue.commands.map { (it.action as PendingLaunchAction.OpenMemo).memoId } shouldBe
                listOf("already-queued", "restored")
        }
    }
}

private class ProbeIntent(
    private val actionValue: String,
    private val stringExtras: Map<String, String> = emptyMap(),
) : Intent() {
    private var mutableFlags: Int = 0

    override fun getAction(): String = actionValue

    override fun getFlags(): Int = mutableFlags

    override fun addFlags(flags: Int): Intent {
        mutableFlags = mutableFlags or flags
        return this
    }

    override fun getStringExtra(name: String): String? = stringExtras[name]
}
