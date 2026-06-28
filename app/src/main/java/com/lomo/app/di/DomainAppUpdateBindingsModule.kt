package com.lomo.app.di

import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateDownloadRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import com.lomo.domain.usecase.GetLatestAppReleaseUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainAppUpdateBindingsModule {
    @Provides
    @Singleton
    fun provideDownloadAndInstallAppUpdateUseCase(
        appUpdateDownloadRepository: AppUpdateDownloadRepository,
    ): DownloadAndInstallAppUpdateUseCase =
        DownloadAndInstallAppUpdateUseCase(appUpdateDownloadRepository)

    @Provides
    @Singleton
    fun provideCancelAppUpdateDownloadUseCase(
        appUpdateDownloadRepository: AppUpdateDownloadRepository,
    ): CancelAppUpdateDownloadUseCase =
        CancelAppUpdateDownloadUseCase(appUpdateDownloadRepository)

    @Provides
    @Singleton
    fun provideCheckStartupAppUpdateUseCase(
        preferencesRepository: PreferencesRepository,
        appUpdateRepository: AppUpdateRepository,
        appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ): CheckStartupAppUpdateUseCase =
        CheckStartupAppUpdateUseCase(
            preferencesRepository = preferencesRepository,
            appUpdateRepository = appUpdateRepository,
            appRuntimeInfoRepository = appRuntimeInfoRepository,
        )

    @Provides
    @Singleton
    fun provideCheckAppUpdateUseCase(
        appUpdateRepository: AppUpdateRepository,
        appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ): CheckAppUpdateUseCase =
        CheckAppUpdateUseCase(
            appUpdateRepository = appUpdateRepository,
            appRuntimeInfoRepository = appRuntimeInfoRepository,
        )

    @Provides
    @Singleton
    fun provideGetLatestAppReleaseUseCase(
        appUpdateRepository: AppUpdateRepository,
    ): GetLatestAppReleaseUseCase =
        GetLatestAppReleaseUseCase(
            appUpdateRepository = appUpdateRepository,
        )
}
