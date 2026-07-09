package com.lomo.domain.usecase

import com.lomo.domain.model.ReminderMarker

class RewriteReminderTokenUseCase {
    operator fun invoke(
        content: String,
        marker: ReminderMarker,
        newToken: String,
    ): String {
        val start = marker.tokenRange.first
        val endExclusive = marker.tokenRange.last + 1
        return content.substring(0, start) + newToken + content.substring(endExclusive)
    }
}
