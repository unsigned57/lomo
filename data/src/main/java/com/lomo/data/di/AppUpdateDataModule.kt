package com.lomo.data.di

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
import java.io.File
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

val appUpdateDataModule = module {
    singleOf(::AppUpdateRepositoryImpl) bind AppUpdateRepository::class
    singleOf(::AppUpdateDownloadRepositoryImpl) bind AppUpdateDownloadRepository::class
    singleOf(::AppRuntimeInfoRepositoryImpl) bind AppRuntimeInfoRepository::class

    single<AppUpdateInstallAttemptStore> {
        JsonFileAppUpdateInstallAttemptStore(File(androidContext().filesDir, "update-install/attempt.json"))
    }
    single<AppUpdateInstallerLauncher> { FileProviderAppUpdateInstallerLauncher(androidContext()) }

    single { AppUpdateTransportOwner.createDefault() }
    single<AppUpdateApkDownloader> { get<AppUpdateTransportOwner>().createDownloader() }
    single<AppUpdateTransportLifecycleRepository> { get<AppUpdateTransportOwner>() }

    single { PackageManagerAppUpdateApkVerifier(androidContext()) }
    single<AppUpdateApkVerifier> { get<PackageManagerAppUpdateApkVerifier>() }
    single<AppUpdateInstallerResultObserver> { get<PackageManagerAppUpdateApkVerifier>() }
}
