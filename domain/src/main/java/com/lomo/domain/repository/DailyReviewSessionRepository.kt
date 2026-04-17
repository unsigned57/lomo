package com.lomo.domain.repository

import com.lomo.domain.model.DailyReviewSession

interface DailyReviewSessionRepository {
    suspend fun getSession(): DailyReviewSession?

    suspend fun saveSession(session: DailyReviewSession)
}
