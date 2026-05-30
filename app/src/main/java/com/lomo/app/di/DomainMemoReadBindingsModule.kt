package com.lomo.app.di

import com.lomo.domain.repository.DailyReviewSessionRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.MemoTrashUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.ObserveSidebarStatisticsUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainMemoReadBindingsModule {
    @Provides
    @Singleton
    fun provideDailyReviewQueryUseCase(
        memoQueryRepository: MemoQueryRepository,
    ): DailyReviewQueryUseCase = DailyReviewQueryUseCase(memoQueryRepository)

    @Provides
    @Singleton
    fun provideDailyReviewSessionUseCase(
        dailyReviewSessionRepository: DailyReviewSessionRepository,
    ): DailyReviewSessionUseCase =
        DailyReviewSessionUseCase(dailyReviewSessionRepository)

    @Provides
    @Singleton
    fun provideMemoStatisticsUseCase(
        memoStatisticsRepository: MemoStatisticsRepository,
    ): MemoStatisticsUseCase =
        MemoStatisticsUseCase(
            memoStatisticsRepository = memoStatisticsRepository,
        )

    @Provides
    @Singleton
    fun provideObserveActiveDayCountUseCase(
        memoStatisticsRepository: MemoStatisticsRepository,
    ): ObserveActiveDayCountUseCase = ObserveActiveDayCountUseCase(memoStatisticsRepository)

    @Provides
    @Singleton
    fun provideObserveSidebarStatisticsUseCase(
        memoStatisticsRepository: MemoStatisticsRepository,
    ): ObserveSidebarStatisticsUseCase = ObserveSidebarStatisticsUseCase(memoStatisticsRepository)

    @Provides
    @Singleton
    fun provideMemoTrashUseCase(
        memoTrashRepository: MemoTrashRepository,
    ): MemoTrashUseCase = MemoTrashUseCase(memoTrashRepository)

    @Provides
    @Singleton
    fun provideObserveDraftTextUseCase(
        preferencesRepository: PreferencesRepository,
    ): ObserveDraftTextUseCase = ObserveDraftTextUseCase(preferencesRepository)

    @Provides
    @Singleton
    fun provideSetDraftTextUseCase(
        preferencesRepository: PreferencesRepository,
    ): SetDraftTextUseCase = SetDraftTextUseCase(preferencesRepository)
}
