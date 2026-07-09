package com.lomo.app.testing.fakes

import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.repository.DailyReviewSessionRepository

class FakeDailyReviewSessionRepository : DailyReviewSessionRepository {
    var session: DailyReviewSession? = null

    override suspend fun getSession(): DailyReviewSession? {
        return session
    }

    override suspend fun saveSession(session: DailyReviewSession) {
        this.session = session
    }
}
