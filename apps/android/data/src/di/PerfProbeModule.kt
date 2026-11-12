package com.lomo.data.di

import com.lomo.data.engine.EnginePerfProbe
import com.lomo.data.engine.PerfProbeReceiver
import com.lomo.data.engine.PerfProbeRegistration
import com.lomo.domain.usecase.DispatcherProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal val perfProbeModule = module {
    factory { EnginePerfProbe(get(), get(), get(), get(), get(), get(), androidContext()) }
    single {
        PerfProbeReceiver(
            measurements = { get<EnginePerfProbe>() },
            probeScope = get(named("ApplicationScope")),
            ioDispatcher = get<DispatcherProvider>().io,
        )
    }
    single(createdAtStart = true) {
        PerfProbeRegistration(androidContext(), get())
    } withOptions {
        onClose { registration -> registration?.close() }
    }
}
