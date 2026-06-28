package com.lomo.app.di

import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncErrorUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainCoreBindingsModule {
    @Provides
    @Singleton
    fun provideValidateMemoContentUseCase(): ValidateMemoContentUseCase = ValidateMemoContentUseCase()

    @Provides
    @Singleton
    fun provideResolveMemoUpdateActionUseCase(): ResolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase()

    @Provides
    @Singleton
    fun provideGitRemoteUrlUseCase(): GitRemoteUrlUseCase = GitRemoteUrlUseCase()

    @Provides
    @Singleton
    fun provideGitSyncErrorUseCase(): GitSyncErrorUseCase = GitSyncErrorUseCase()

    @Provides
    @Singleton
    fun provideResolveMainMemoQueryUseCase(): ResolveMainMemoQueryUseCase = ResolveMainMemoQueryUseCase()

    @Provides
    @Singleton
    fun provideMarkReminderDoneUseCase(
        reminderCoordinator: ReminderCoordinator,
    ): MarkReminderDoneUseCase = MarkReminderDoneUseCase(reminderCoordinator)

    @Provides
    @Singleton
    fun provideGetCurrentAppVersionUseCase(
        appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ): GetCurrentAppVersionUseCase = GetCurrentAppVersionUseCase(appRuntimeInfoRepository)
}
