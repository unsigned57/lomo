package com.lomo.data.di

import android.content.Context
import com.lomo.data.reminder.AlarmManagerReminderCoordinator
import com.lomo.domain.repository.MemoRepository
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
        memoRepository: MemoRepository,
    ): ReminderCoordinator =
        AlarmManagerReminderCoordinator(
            context = context,
            memoQueryRepository = memoRepository,
            memoMutationRepository = memoRepository,
        )

    @Provides
    @Singleton
    fun provideMemoQueryRepository(
        memoRepository: MemoRepository,
    ): com.lomo.domain.repository.MemoQueryRepository = memoRepository

    @Provides
    @Singleton
    fun provideMemoMutationRepository(
        memoRepository: MemoRepository,
    ): com.lomo.domain.repository.MemoMutationRepository = memoRepository
}

