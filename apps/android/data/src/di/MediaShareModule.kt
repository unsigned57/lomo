package com.lomo.data.di

import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.lan.AndroidLanDeviceKey
import com.lomo.data.engine.lan.LanDeviceKey
import com.lomo.data.engine.lan.LanRuntimeCoordinator
import com.lomo.data.engine.lan.RustLanShareService
import com.lomo.data.media.AudioPlaybackUriResolverImpl
import com.lomo.data.media.AudioRecorder
import com.lomo.data.share.NsdDiscoveryService
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.VoiceRecordingRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val mediaShareModule = module {
    single { AudioRecorder(androidContext(), get()) } bind VoiceRecordingRepository::class
    single {
        val session = get<EngineReadinessRepository>()
        AudioPlaybackUriResolverImpl(androidContext(), { session.mount.value }, get())
    } bind AudioPlaybackResolverRepository::class

    single<LanDeviceKey> { AndroidLanDeviceKey() }
    single { NsdDiscoveryService(androidContext()) }
    single {
        LanRuntimeCoordinator(
            context = androidContext(),
            engine = get<ManagedEngineSession>(),
            discovery = get(),
            deviceKey = get(),
            scope = get(named("ApplicationScope")),
        )
    }
    single<LanShareService> {
        RustLanShareService(
            context = androidContext(),
            preferences = get(),
            engine = get(),
            runtime = get(),
            deviceKey = get(),
            appScope = get(named("ApplicationScope")),
            dispatcherProvider = get(),
        )
    }
}
