package com.lomo.data.di

import android.content.Context
import com.lomo.data.reminder.AlarmManagerReminderCoordinator
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.ReminderCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
    @Provides
    @Singleton
    fun provideReminderCoordinator(
        @ApplicationContext context: Context,
        memoQueryRepository: MemoQueryRepository,
        memoMutationRepository: MemoMutationRepository,
    ): ReminderCoordinator =
        AlarmManagerReminderCoordinator(
            context = context,
            memoQueryRepository = memoQueryRepository,
            memoMutationRepository = memoMutationRepository,
        )
}
