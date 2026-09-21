package com.lomo.data.di

import com.lomo.domain.repository.AppBackgroundWorkRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.bind

annotation class ApplicationScope

class ApplicationBackgroundWorkOwner(
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : AppBackgroundWorkRepository {
    val scope: CoroutineScope =
        // behavior-contract: unmanaged-scope-ok: process-lifetime owner cancelled via cancelAppBackgroundWork
        CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    override fun cancelAppBackgroundWork() {
        scope.cancel()
    }
}

val applicationScopeModule = module {
    single { ApplicationBackgroundWorkOwner(get()) } bind AppBackgroundWorkRepository::class
    single(named("ApplicationScope")) { get<ApplicationBackgroundWorkOwner>().scope }
}
