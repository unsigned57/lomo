package com.lomo.data.di

import android.content.Context
import com.lomo.data.repository.AppRuntimeInfoRepositoryImpl
import com.lomo.data.repository.AppUpdateApkDownloader
import com.lomo.data.repository.AppUpdateApkVerifier
import com.lomo.data.repository.AppUpdateDownloadRepositoryImpl
import com.lomo.data.repository.AppUpdateInstallAttemptStore
import com.lomo.data.repository.AppUpdateInstallerLauncher
import com.lomo.data.repository.AppUpdateInstallerResultObserver
import com.lomo.data.repository.AppUpdateRepositoryImpl
import com.lomo.data.repository.AppUpdateTransportOwner
import com.lomo.data.repository.FileProviderAppUpdateInstallerLauncher
import com.lomo.data.repository.JsonFileAppUpdateInstallAttemptStore
import com.lomo.data.repository.PackageManagerAppUpdateApkVerifier
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateDownloadRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppUpdateTransportLifecycleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppUpdateRepositoryModule {
    @Provides
    @Singleton
    fun provideAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository = impl

    @Provides
    @Singleton
    fun provideAppUpdateDownloadRepository(
        impl: AppUpdateDownloadRepositoryImpl,
    ): AppUpdateDownloadRepository = impl

    @Provides
    @Singleton
    fun provideAppRuntimeInfoRepository(impl: AppRuntimeInfoRepositoryImpl): AppRuntimeInfoRepository = impl

    @Provides
    @Singleton
    internal fun provideAppUpdateInstallAttemptStore(
        @ApplicationContext context: Context,
    ): AppUpdateInstallAttemptStore =
        JsonFileAppUpdateInstallAttemptStore(File(context.filesDir, "update-install/attempt.json"))

    @Provides
    @Singleton
    internal fun provideAppUpdateInstallerLauncher(
        @ApplicationContext context: Context,
    ): AppUpdateInstallerLauncher = FileProviderAppUpdateInstallerLauncher(context)
}

@Module
@InstallIn(SingletonComponent::class)
object AppUpdateTransportModule {
    @Provides
    @Singleton
    internal fun provideAppUpdateTransportOwner(): AppUpdateTransportOwner = AppUpdateTransportOwner.createDefault()

    @Provides
    @Singleton
    internal fun provideAppUpdateApkDownloader(
        transportOwner: AppUpdateTransportOwner,
    ): AppUpdateApkDownloader = transportOwner.createDownloader()

    @Provides
    @Singleton
    internal fun provideAppUpdateTransportLifecycleRepository(
        transportOwner: AppUpdateTransportOwner,
    ): AppUpdateTransportLifecycleRepository = transportOwner

    @Provides
    @Singleton
    internal fun providePackageManagerAppUpdateApkVerifier(
        @ApplicationContext context: Context,
    ): PackageManagerAppUpdateApkVerifier = PackageManagerAppUpdateApkVerifier(context)

    @Provides
    @Singleton
    internal fun provideAppUpdateApkVerifier(
        verifier: PackageManagerAppUpdateApkVerifier,
    ): AppUpdateApkVerifier = verifier

    @Provides
    @Singleton
    internal fun provideAppUpdateInstallerResultObserver(
        verifier: PackageManagerAppUpdateApkVerifier,
    ): AppUpdateInstallerResultObserver = verifier
}
