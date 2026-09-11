package com.lomo.app.di

import org.koin.dsl.module
import org.koin.core.qualifier.named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val appScopeModule = module {
    // behavior-contract: unmanaged-scope-ok: process-lifetime named AppScope
    single(named("AppScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
