package com.lomo.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appScopeModule = module {
    // behavior-contract: unmanaged-scope-ok: process-lifetime named AppScope
    single(named("AppScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
