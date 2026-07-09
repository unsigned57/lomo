package com.lomo.data.di

import com.lomo.domain.repository.AppBackgroundWorkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.bind

annotation class ApplicationScope

class ApplicationBackgroundWorkOwner : AppBackgroundWorkRepository {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun cancelAppBackgroundWork() {
        scope.cancel()
    }
}

val applicationScopeModule = module {
    single { ApplicationBackgroundWorkOwner() } bind AppBackgroundWorkRepository::class
    single(named("ApplicationScope")) { get<ApplicationBackgroundWorkOwner>().scope }
}
