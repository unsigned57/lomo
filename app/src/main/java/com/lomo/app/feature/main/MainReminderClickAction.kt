package com.lomo.app.feature.main

import com.lomo.domain.model.ReminderMarker

internal fun createMainReminderDoneClickAction(
    memoId: String,
    onReminderDone: (String, String) -> Unit,
): (ReminderMarker) -> Unit =
    { reminder ->
        onReminderDone(memoId, reminder.raw)
    }
