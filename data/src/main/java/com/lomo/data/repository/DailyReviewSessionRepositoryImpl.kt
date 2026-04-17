package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.repository.DailyReviewSessionRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyReviewSessionRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : DailyReviewSessionRepository {
        override suspend fun getSession(): DailyReviewSession? {
            val date = dataStore.dailyReviewSessionDate.first()?.let(::parseDate) ?: return null
            val seed = dataStore.dailyReviewSessionSeed.first() ?: return null
            val pageIndex = dataStore.dailyReviewSessionPageIndex.first() ?: 0
            return DailyReviewSession(
                date = date,
                seed = seed,
                pageIndex = pageIndex,
            )
        }

        override suspend fun saveSession(session: DailyReviewSession) {
            dataStore.updateDailyReviewSession(
                date = session.date.toString(),
                seed = session.seed,
                pageIndex = session.pageIndex,
            )
        }

        private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
    }
