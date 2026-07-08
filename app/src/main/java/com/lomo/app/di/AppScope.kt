package com.lomo.app.di

import org.koin.dsl.module
import org.koin.core.qualifier.named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val appScopeModule = module {
    single(named("AppScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
