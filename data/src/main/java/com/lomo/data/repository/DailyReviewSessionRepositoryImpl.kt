package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.repository.DailyReviewSessionRepository
import kotlinx.coroutines.flow.combine
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
        override suspend fun getSession(): DailyReviewSession? =
            combine(
                dataStore.dailyReviewSessionDate,
                dataStore.dailyReviewSessionSeed,
                dataStore.dailyReviewSessionPageIndex,
            ) { dateStr, seed, pageIndex ->
                val date = dateStr?.let(::parseDate) ?: return@combine null
                val resolvedSeed = seed ?: return@combine null
                DailyReviewSession(
                    date = date,
                    seed = resolvedSeed,
                    pageIndex = pageIndex ?: 0,
                )
            }.first()

        override suspend fun saveSession(session: DailyReviewSession) {
            dataStore.updateDailyReviewSession(
                date = session.date.toString(),
                seed = session.seed,
                pageIndex = session.pageIndex,
            )
        }

        private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
    }
