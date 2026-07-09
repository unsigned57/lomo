package com.lomo.data.recording

import com.lomo.domain.model.RecordingDeepLink

internal object RecordingIntents {
    const val NOTIFICATION_CHANNEL_ID = "lomo.recording"
    const val ONGOING_NOTIFICATION_ID = 41001
    const val SAVED_NOTIFICATION_ID = 41002

    const val ACTION_STOP = "com.lomo.recording.action.STOP"
    const val ACTION_CANCEL = "com.lomo.recording.action.CANCEL"
    const val ACTION_OPEN_SAVED_MEMO = RecordingDeepLink.ACTION_OPEN_SAVED_MEMO
    const val EXTRA_MEMO_ID = RecordingDeepLink.EXTRA_MEMO_ID
}
