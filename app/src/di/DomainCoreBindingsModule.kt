package com.lomo.app.di

import com.lomo.domain.usecase.GetCurrentAppBuildVersionUseCase
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncErrorUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import org.koin.dsl.module

val domainCoreModule = module {
    single { ValidateMemoContentUseCase() }
    single { ResolveMemoUpdateActionUseCase() }
    single { GitRemoteUrlUseCase() }
    single { GitSyncErrorUseCase() }
    single { ResolveMainMemoQueryUseCase() }
    single { MarkReminderDoneUseCase(get()) }
    single { GetCurrentAppBuildVersionUseCase(get()) }
    single { GetCurrentAppVersionUseCase(get()) }
}
