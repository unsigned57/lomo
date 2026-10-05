package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.engine.store.StorePort
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.ReminderCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

private const val TAG = "ReminderAlarmReceiver"

class ReminderAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val reminderNotifier: ReminderNotifier by inject()
    private val storePort: StorePort by inject()
    private val engineReadiness: EngineReadinessRepository by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        val memoId = intent.getStringExtra(ReminderIntents.EXTRA_MEMO_ID) ?: return
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val occurrenceId = intent.getStringExtra(ReminderIntents.EXTRA_OCCURRENCE_ID) ?: return
        val pendingResult = goAsync()

        asyncRunner.launch(
            pendingResult,
            onResult = { result ->
                if (result is ReminderReceiverWorkResult.Failed) {
                    Timber.tag(TAG).w(result.cause, "Reminder alarm work failed for memo %s", memoId)
                }
            },
        ) {
            // A firing alarm can spawn a cold process; engine acquisition is explicit, so the
            // receiver must request it before touching any engine-backed projection.
            if (engineReadiness.requestEngineStart() !is EngineReadiness.Ready) {
                Timber.tag(TAG).i(
                    "Reminder alarm skipped: workspace engine is not ready (memo=%s)",
                    memoId,
                )
                return@launch
            }
            val summary = storePort.getMemo(memoId)?.summary ?: return@launch
            // A durable projection row can outlive an alarm (trash) or be visible during a
            // pending create. Neither state is an executable reminder fact.
            if (!reminderExecutionAllowed(summary)) return@launch
            val marker = projectedReminderFor(summary, reminderId) ?: return@launch
            if (marker.isExhausted) return@launch
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent()
            // The notification body is the store's plain-text preview projection; the full
            // Markdown body is never rendered at fire time.
            val delivery =
                reminderNotifier.showFor(memoId, marker, occurrenceId, summary.bodyPreview, launchIntent)
            when (delivery) {
                ReminderDelivery.Delivered ->
                    // recordFired consumes the occurrence only after a real delivery; a
                    // non-delivery leaves it pending so the next plan can re-offer it.
                    reminderCoordinator.recordFired(memoId, reminderId)
                is ReminderDelivery.NotDelivered ->
                    Timber.tag(TAG).w(
                        "Reminder notification not delivered (memo=%s, cause=%s); occurrence stays pending",
                        memoId,
                        delivery.cause,
                    )
            }
        }
    }
}

/** Only durable, active projection rows may cross into the OS notification side effect. */
internal fun reminderExecutionAllowed(summary: StoreMemoSummary): Boolean = !summary.isTrashed && !summary.isPending

/** Resolves the alarm identity from the same materialized memo projection used for scheduling. */
internal fun projectedReminderFor(summary: StoreMemoSummary, reminderId: String): ReminderMarker? =
    summary.reminders.singleOrNull { it.reference.opaqueId == reminderId }
