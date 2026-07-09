package com.lomo.app.di

import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import com.lomo.domain.usecase.GetLatestAppReleaseUseCase
import org.koin.dsl.module

val domainAppUpdateModule = module {
    single { DownloadAndInstallAppUpdateUseCase(get()) }
    single { CancelAppUpdateDownloadUseCase(get()) }
    single { CheckStartupAppUpdateUseCase(get(), get(), get()) }
    single { CheckAppUpdateUseCase(get(), get()) }
    single { GetLatestAppReleaseUseCase(get()) }
}
