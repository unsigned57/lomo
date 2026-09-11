package com.lomo.data.reminder

import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.repository.ReminderTokenFactory
import com.lomo.nativebridge.ReminderTokenBuildRequest
import com.lomo.nativebridge.ReminderTokenMutationKind
import com.lomo.nativebridge.buildReminderToken
import com.lomo.nativebridge.planReminderTokenMutation
import java.time.LocalDateTime

/** Conversion-only adapter: domain fields → workspace owner token grammar. */
class OwnerReminderTokenFactory : ReminderTokenFactory {
    override fun buildInsertToken(
        dueAt: LocalDateTime,
        repeatCount: Int,
        intervalMinutes: Int,
        recurrence: Recurrence,
    ): String =
        buildReminderToken(
            ReminderTokenBuildRequest(
                dueAtLocal = dueAt.format(ReminderMarker.TIMESTAMP_FORMAT),
                repeatCount = repeatCount.toUInt(),
                firedCount = 0u,
                done = false,
                intervalMinutes = intervalMinutes.toUInt(),
                recurrenceCode = recurrence.code,
            ),
        )

    override fun planMarkDone(currentToken: String): String =
        planReminderTokenMutation(
            currentToken = currentToken,
            mutation = ReminderTokenMutationKind.MARK_DONE,
        )

    override fun planRecordFired(currentToken: String): String =
        planReminderTokenMutation(
            currentToken = currentToken,
            mutation = ReminderTokenMutationKind.RECORD_FIRED,
        )
}
